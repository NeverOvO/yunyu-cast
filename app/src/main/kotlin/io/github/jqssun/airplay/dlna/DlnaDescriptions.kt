package io.github.jqssun.airplay.dlna

/** UPnP AV service descriptions, generated from the same small action surface implemented by the renderer. */
internal object DlnaDescriptions {
    const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    val services = linkedMapOf("AVTransport" to AVT, "RenderingControl" to RCS, "ConnectionManager" to CMS)
    // MIME capabilities only: no fabricated DLNA profile/DRM certification flags.
    val sinkProtocols = listOf("video/mp4", "video/mpeg", "video/mp2t", "video/MP2T", "video/quicktime",
        "application/vnd.apple.mpegurl", "application/x-mpegURL", "audio/mpeg", "audio/mp4", "audio/aac")
        .joinToString(",") { "http-get:*:$it:*" }

    fun device(name: String, uuid: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion><device>
<deviceType>$DEVICE</deviceType><friendlyName>${xml(name)}</friendlyName>
<manufacturer>Yunyu</manufacturer><modelDescription>Yunyu Cast network media receiver</modelDescription>
<modelName>Yunyu Cast</modelName><modelNumber>0.2</modelNumber><UDN>$uuid</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>${services.entries.joinToString("") { (key, type) -> """<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$key</serviceId><SCPDURL>/$key/scpd.xml</SCPDURL><controlURL>/$key/control</controlURL><eventSubURL>/$key/event</eventSubURL></service>""" }}</serviceList>
</device></root>"""

    private data class Arg(val name: String, val direction: String, val state: String)
    private data class Action(val name: String, val args: List<Arg>)
    private data class Variable(val name: String, val type: String = "string", val event: Boolean = false,
                                val values: List<String> = emptyList(), val range: Boolean = false)
    private fun input(name: String, state: String = name) = Arg(name, "in", state)
    private fun output(name: String, state: String = name) = Arg(name, "out", state)
    private val instance = input("InstanceID", "A_ARG_TYPE_InstanceID")
    private val channel = input("Channel", "A_ARG_TYPE_Channel")
    private fun action(name: String, vararg args: Arg) = Action(name, args.toList())
    private fun transport(name: String, vararg args: Arg) = Action(name, listOf(instance) + args)
    private fun render(name: String, vararg args: Arg) = Action(name, listOf(instance, channel) + args)

    private val avtActions = listOf(
        transport("SetAVTransportURI", input("CurrentURI", "AVTransportURI"), input("CurrentURIMetaData", "AVTransportURIMetaData")),
        transport("GetMediaInfo", output("NrTracks", "NumberOfTracks"), output("MediaDuration", "CurrentMediaDuration"),
            output("CurrentURI", "AVTransportURI"), output("CurrentURIMetaData", "AVTransportURIMetaData"),
            output("NextURI", "NextAVTransportURI"), output("NextURIMetaData", "NextAVTransportURIMetaData"),
            output("PlayMedium", "PlaybackStorageMedium"), output("RecordMedium", "RecordStorageMedium"), output("WriteStatus", "RecordMediumWriteStatus")),
        transport("GetTransportInfo", output("CurrentTransportState", "TransportState"), output("CurrentTransportStatus", "TransportStatus"), output("CurrentSpeed", "TransportPlaySpeed")),
        transport("GetPositionInfo", output("Track", "CurrentTrack"), output("TrackDuration", "CurrentTrackDuration"),
            output("TrackMetaData", "CurrentTrackMetaData"), output("TrackURI", "CurrentTrackURI"),
            output("RelTime", "RelativeTimePosition"), output("AbsTime", "AbsoluteTimePosition"),
            output("RelCount", "RelativeCounterPosition"), output("AbsCount", "AbsoluteCounterPosition")),
        transport("GetDeviceCapabilities", output("PlayMedia", "PossiblePlaybackStorageMedia"), output("RecMedia", "PossibleRecordStorageMedia"), output("RecQualityModes", "PossibleRecordQualityModes")),
        transport("GetTransportSettings", output("PlayMode", "CurrentPlayMode"), output("RecQualityMode", "CurrentRecordQualityMode")),
        transport("Stop"), transport("Play", input("Speed", "TransportPlaySpeed")), transport("Pause"),
        transport("Seek", input("Unit", "A_ARG_TYPE_SeekMode"), input("Target", "A_ARG_TYPE_SeekTarget")),
        transport("GetCurrentTransportActions", output("Actions", "CurrentTransportActions")),
        transport("SetPlayMode", input("NewPlayMode", "CurrentPlayMode")),
    )
    private val avtVariables = listOf(
        Variable("TransportState", values = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")),
        Variable("TransportStatus", values = listOf("OK", "ERROR_OCCURRED")),
        Variable("PlaybackStorageMedium", values = listOf("NETWORK", "NONE")), Variable("RecordStorageMedium", values = listOf("NOT_IMPLEMENTED")),
        Variable("PossiblePlaybackStorageMedia"), Variable("PossibleRecordStorageMedia"), Variable("CurrentPlayMode", values = listOf("NORMAL")),
        Variable("TransportPlaySpeed", values = listOf("1")), Variable("RecordMediumWriteStatus", values = listOf("NOT_IMPLEMENTED")),
        Variable("CurrentRecordQualityMode", values = listOf("NOT_IMPLEMENTED")), Variable("PossibleRecordQualityModes"),
        Variable("NumberOfTracks", "ui4"), Variable("CurrentTrack", "ui4"), Variable("CurrentTrackDuration"), Variable("CurrentMediaDuration"),
        Variable("CurrentTrackMetaData"), Variable("CurrentTrackURI", "uri"), Variable("AVTransportURI", "uri"), Variable("AVTransportURIMetaData"),
        Variable("NextAVTransportURI", "uri"), Variable("NextAVTransportURIMetaData"), Variable("RelativeTimePosition"), Variable("AbsoluteTimePosition"),
        Variable("RelativeCounterPosition", "i4"), Variable("AbsoluteCounterPosition", "i4"), Variable("CurrentTransportActions"),
        Variable("LastChange", event = true), Variable("A_ARG_TYPE_SeekMode", values = listOf("REL_TIME", "ABS_TIME")),
        Variable("A_ARG_TYPE_SeekTarget"), Variable("A_ARG_TYPE_InstanceID", "ui4"),
    )
    private val rcsActions = listOf(
        action("ListPresets", instance, output("CurrentPresetNameList", "PresetNameList")),
        action("SelectPreset", instance, input("PresetName", "A_ARG_TYPE_PresetName")),
        render("GetVolume", output("CurrentVolume", "Volume")), render("SetVolume", input("DesiredVolume", "Volume")),
        render("GetMute", output("CurrentMute", "Mute")), render("SetMute", input("DesiredMute", "Mute")),
    )
    private val rcsVariables = listOf(Variable("LastChange", event = true), Variable("PresetNameList"),
        Variable("Mute", "boolean"), Variable("Volume", "ui2", range = true), Variable("A_ARG_TYPE_InstanceID", "ui4"),
        Variable("A_ARG_TYPE_Channel", values = listOf("Master")), Variable("A_ARG_TYPE_PresetName", values = listOf("FactoryDefaults")))
    private val cmsActions = listOf(
        action("GetProtocolInfo", output("Source", "SourceProtocolInfo"), output("Sink", "SinkProtocolInfo")),
        action("GetCurrentConnectionIDs", output("ConnectionIDs", "CurrentConnectionIDs")),
        action("GetCurrentConnectionInfo", input("ConnectionID", "A_ARG_TYPE_ConnectionID"),
            output("RcsID", "A_ARG_TYPE_RcsID"), output("AVTransportID", "A_ARG_TYPE_AVTransportID"),
            output("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), output("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
            output("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), output("Direction", "A_ARG_TYPE_Direction"), output("Status", "A_ARG_TYPE_ConnectionStatus")),
    )
    private val cmsVariables = listOf(Variable("SourceProtocolInfo", event = true), Variable("SinkProtocolInfo", event = true),
        Variable("CurrentConnectionIDs", event = true), Variable("A_ARG_TYPE_ConnectionStatus", values = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        Variable("A_ARG_TYPE_ConnectionManager"), Variable("A_ARG_TYPE_Direction", values = listOf("Input", "Output")),
        Variable("A_ARG_TYPE_ProtocolInfo"), Variable("A_ARG_TYPE_ConnectionID", "i4"), Variable("A_ARG_TYPE_AVTransportID", "i4"), Variable("A_ARG_TYPE_RcsID", "i4"))

    fun scpd(service: String): String {
        val (actions, variables) = when (service) {
            "AVTransport" -> avtActions to avtVariables
            "RenderingControl" -> rcsActions to rcsVariables
            "ConnectionManager" -> cmsActions to cmsVariables
            else -> error("Unknown service")
        }
        return """<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>${actions.joinToString("") { a ->
            "<action><name>${a.name}</name>" + if (a.args.isEmpty()) "</action>" else "<argumentList>${a.args.joinToString("") { "<argument><name>${it.name}</name><direction>${it.direction}</direction><relatedStateVariable>${it.state}</relatedStateVariable></argument>" }}</argumentList></action>"
        }}</actionList><serviceStateTable>${variables.joinToString("") { v ->
            "<stateVariable sendEvents=\"${if(v.event) "yes" else "no"}\"><name>${v.name}</name><dataType>${v.type}</dataType>" +
                (if (v.values.isEmpty()) "" else "<allowedValueList>${v.values.joinToString("") { "<allowedValue>$it</allowedValue>" }}</allowedValueList>") +
                (if (v.range) "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>" else "") + "</stateVariable>"
        }}</serviceStateTable></scpd>"""
    }
}
