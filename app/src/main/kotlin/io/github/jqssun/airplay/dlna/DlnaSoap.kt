package io.github.jqssun.airplay.dlna

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

internal object DlnaSoap {
    data class Command(val action: String, val namespace: String, val args: Map<String, String>)

    fun parse(body: String): Command {
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            throw DlnaActionException(402, "Invalid XML")
        }
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(StringReader(body))
            var inBody = false
            var action: String? = null
            var namespace = ""
            var actionDepth = -1
            var argument: String? = null
            var text = StringBuilder()
            val args = linkedMapOf<String, String>()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.DOCDECL -> throw DlnaActionException(402, "Invalid XML")
                    XmlPullParser.START_TAG -> {
                        if (p.name == "Body" && p.namespace == "http://schemas.xmlsoap.org/soap/envelope/") inBody = true
                        else if (inBody && action == null) { action = p.name; namespace = p.namespace; actionDepth = p.depth }
                        else if (action != null && p.depth == actionDepth + 1) { argument = p.name; text = StringBuilder() }
                        else if (action != null && p.depth > actionDepth + 1) throw DlnaActionException(402, "Invalid Args")
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (argument != null) text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if (argument != null && p.depth == actionDepth + 1) {
                            if (args.put(argument, text.toString()) != null) throw DlnaActionException(402, "Invalid Args")
                            argument = null
                        }
                        if (action != null && p.depth == actionDepth) break
                    }
                }
                p.next()
            }
            return Command(action ?: throw DlnaActionException(402, "Invalid Args"), namespace, args)
        } catch (e: DlnaActionException) { throw e }
        catch (_: Exception) { throw DlnaActionException(402, "Invalid XML") }
    }

    fun execute(service: String, command: Command, controller: DlnaPlayerController): String {
        val type = DlnaDescriptions.services[service] ?: throw DlnaActionException(401, "Invalid Action")
        if (command.namespace != type) throw DlnaActionException(401, "Invalid Action")
        val a = command.args
        fun arg(name: String): String = a[name] ?: throw DlnaActionException(402, "Invalid Args")
        if (service != "ConnectionManager" && arg("InstanceID") != "0") throw DlnaActionException(718, "Invalid InstanceID")
        if (service == "RenderingControl" && command.action !in listOf("ListPresets", "SelectPreset") && arg("Channel") != "Master") {
            throw DlnaActionException(402, "Invalid Channel")
        }
        val s = controller.snapshot()
        val result: Map<String, String> = when (service) {
            "AVTransport" -> when (command.action) {
                "SetAVTransportURI" -> {
                    val uri = arg("CurrentURI").trim()
                    val metadata = arg("CurrentURIMetaData")
                    if (uri.isNotEmpty()) {
                        val parsed = try { URI(uri) } catch (_: Exception) { throw DlnaActionException(716, "Resource not found") }
                        if (uri.length > 16_384 || parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
                            throw DlnaActionException(714, "Illegal MIME-type")
                        }
                    }
                    if (metadata.length > 262_144) throw DlnaActionException(402, "Metadata too large")
                    controller.setUri(uri, metadata)
                    emptyMap()
                }
                "Play" -> {
                    if (arg("Speed") != "1") throw DlnaActionException(717, "Play speed not supported")
                    if (s.uri.isEmpty()) throw DlnaActionException(701, "Transition not available")
                    controller.play(); emptyMap()
                }
                "Pause" -> {
                    if (s.state !in setOf("PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK")) throw DlnaActionException(701, "Transition not available")
                    controller.pause(); emptyMap()
                }
                "Stop" -> { controller.stop(); emptyMap() }
                "Seek" -> {
                    if (arg("Unit") !in setOf("REL_TIME", "ABS_TIME")) throw DlnaActionException(710, "Seek mode not supported")
                    if (s.uri.isEmpty()) throw DlnaActionException(701, "Transition not available")
                    val target = parseTime(arg("Target"))
                    if (s.durationMs > 0 && target > s.durationMs) throw DlnaActionException(711, "Illegal seek target")
                    controller.seek(target); emptyMap()
                }
                "GetTransportInfo" -> linkedMapOf("CurrentTransportState" to s.state, "CurrentTransportStatus" to s.transportStatus, "CurrentSpeed" to "1")
                "GetPositionInfo" -> linkedMapOf("Track" to if(s.uri.isEmpty()) "0" else "1", "TrackDuration" to dlnaTime(s.durationMs),
                    "TrackMetaData" to s.metadata, "TrackURI" to s.uri, "RelTime" to dlnaTime(s.positionMs), "AbsTime" to dlnaTime(s.positionMs), "RelCount" to "2147483647", "AbsCount" to "2147483647")
                "GetMediaInfo" -> linkedMapOf("NrTracks" to if(s.uri.isEmpty()) "0" else "1", "MediaDuration" to dlnaTime(s.durationMs), "CurrentURI" to s.uri,
                    "CurrentURIMetaData" to s.metadata, "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to if(s.uri.isEmpty()) "NONE" else "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
                "GetDeviceCapabilities" -> linkedMapOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
                "GetTransportSettings" -> linkedMapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
                "GetCurrentTransportActions" -> mapOf("Actions" to actions(s))
                "SetPlayMode" -> { if (arg("NewPlayMode") != "NORMAL") throw DlnaActionException(712, "Play mode not supported"); emptyMap() }
                else -> throw DlnaActionException(401, "Invalid Action")
            }
            "RenderingControl" -> when (command.action) {
                "GetVolume" -> mapOf("CurrentVolume" to s.volume.coerceIn(0, 100).toString())
                "SetVolume" -> {
                    val volume = arg("DesiredVolume").toIntOrNull()?.takeIf { it in 0..100 } ?: throw DlnaActionException(402, "Invalid Args")
                    controller.setVolume(volume); emptyMap()
                }
                "GetMute" -> mapOf("CurrentMute" to if(s.muted) "1" else "0")
                "SetMute" -> {
                    val mute = when(arg("DesiredMute").lowercase()) { "1", "true", "yes" -> true; "0", "false", "no" -> false; else -> throw DlnaActionException(402, "Invalid Args") }
                    controller.setMute(mute); emptyMap()
                }
                "ListPresets" -> mapOf("CurrentPresetNameList" to "FactoryDefaults")
                "SelectPreset" -> {
                    if (arg("PresetName") != "FactoryDefaults") throw DlnaActionException(701, "Invalid Name")
                    controller.setVolume(100); controller.setMute(false); emptyMap()
                }
                else -> throw DlnaActionException(401, "Invalid Action")
            }
            "ConnectionManager" -> when (command.action) {
                "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to DlnaDescriptions.sinkProtocols)
                "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
                "GetCurrentConnectionInfo" -> {
                    if (arg("ConnectionID") != "0") throw DlnaActionException(706, "Invalid connection reference")
                    linkedMapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "http-get:*:*:*", "PeerConnectionManager" to "",
                        "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
                }
                else -> throw DlnaActionException(401, "Invalid Action")
            }
            else -> throw DlnaActionException(401, "Invalid Action")
        }
        return envelope("<u:${command.action}Response xmlns:u=\"$type\">${result.entries.joinToString("") { "<${it.key}>${xml(it.value)}</${it.key}>" }}</u:${command.action}Response>")
    }

    fun fault(code: Int, message: String): String = envelope("""<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${xml(message)}</errorDescription></UPnPError></detail></s:Fault>""")
    private fun envelope(body: String) = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$body</s:Body></s:Envelope>"""
    private fun parseTime(text: String): Long {
        val m = Regex("^(\\d{1,6}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,3}))?$").matchEntire(text)
            ?: throw DlnaActionException(711, "Illegal seek target")
        return (m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000 +
            m.groupValues[4].padEnd(3, '0').toLong()
    }
    private fun actions(s: DlnaPlaybackSnapshot) = when(s.state) {
        "NO_MEDIA_PRESENT" -> ""
        "PLAYING", "TRANSITIONING" -> "Pause,Stop,Seek"
        else -> "Play,Stop,Seek"
    }

    fun event(service: String, s: DlnaPlaybackSnapshot): String {
        val values = when(service) {
            "AVTransport" -> {
                val fields = linkedMapOf("TransportState" to s.state, "TransportStatus" to s.transportStatus, "TransportPlaySpeed" to "1",
                    "AVTransportURI" to s.uri, "AVTransportURIMetaData" to s.metadata, "CurrentTrackURI" to s.uri, "CurrentTrackMetaData" to s.metadata,
                    "CurrentTrack" to if(s.uri.isEmpty()) "0" else "1", "NumberOfTracks" to if(s.uri.isEmpty()) "0" else "1",
                    "CurrentTrackDuration" to dlnaTime(s.durationMs), "CurrentMediaDuration" to dlnaTime(s.durationMs), "CurrentPlayMode" to "NORMAL",
                    "PlaybackStorageMedium" to if(s.uri.isEmpty()) "NONE" else "NETWORK", "CurrentTransportActions" to actions(s))
                mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">${fields.entries.joinToString("") { "<${it.key} val=\"${xml(it.value)}\"/>" }}</InstanceID></Event>")
            }
            "RenderingControl" -> mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\"><Volume channel=\"Master\" val=\"${s.volume.coerceIn(0,100)}\"/><Mute channel=\"Master\" val=\"${if(s.muted) 1 else 0}\"/></InstanceID></Event>")
            else -> mapOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to DlnaDescriptions.sinkProtocols, "CurrentConnectionIDs" to "0")
        }
        return """<?xml version="1.0" encoding="utf-8"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">${values.entries.joinToString("") { "<e:property><${it.key}>${xml(it.value)}</${it.key}></e:property>" }}</e:propertyset>"""
    }
}
