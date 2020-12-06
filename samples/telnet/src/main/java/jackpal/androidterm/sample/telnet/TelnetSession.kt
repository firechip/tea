package jackpal.androidterm.sample.telnet

import android.util.Log
import jackpal.androidterm.emulatorview.TermSession
import java.io.InputStream
import java.io.OutputStream

/**
 * A rudimentary Telnet client implemented as a subclass of TermSession.
 *
 * Telnet, as specified in RFC 854, is a fairly simple protocol: for the
 * most part, we send and receive streams of bytes which can be fed directly
 * into the terminal emulator.  However, there are a handful of complications:
 *
 * - The standard says that CR (ASCII carriage return) must either be
 * translated into the network standard newline sequence CR LF, or be
 * followed immediately by NUL.
 * - (byte) 255, called IAC in the standard, introduces Telnet command
 * sequences which can be used to negotiate options, perform certain
 * actions on the "Network Virtual Terminal" which the standard defines,
 * or do flow control.
 * - By default, the protocol spoken is designed to accommodate a half-duplex
 * terminal on either end, so we should be able to buffer output and
 * send it on a trigger (the sequence IAC GA).
 * - By default, we're expected to be able to echo local keyboard input into
 * our own output.
 *
 * To solve these problems, we filter the input from the network to catch
 * and implement Telnet commands via the processInput() method.  Similarly, we
 * filter the output from TermSession by overriding write() to modify CR as
 * required by the standard, and pass a buffer with manually controllable
 * flushing to the TermSession to use as its output stream.
 *
 * In addition to the base Telnet protocol, we implement two options:
 * the ECHO option (RFC 857) for enabling echoing of input across the network,
 * and the SUPPRESS-GO-AHEAD option (RFC 858) for omitting half-duplex flow
 * control.  Both of these are commonly available from servers, and make our
 * lives easier.
 */
class TelnetSession(termIn: InputStream?, termOut: OutputStream?) : TermSession() {
    // Whether we believe the remote end implements the telnet protocol
    private var peerIsTelnetd = false
    private var peerEchoesInput = false

    /* RFC 854 says false is the default, but that makes the client effectively
       useless for connecting to random non-Telnet servers for debugging */
    private var peerSuppressedGoAhead = true
    private var echoInput = false

    /* RFC 854 says false is the default, but that makes the client effectively
       useless for connecting to random non-Telnet servers for debugging */
    private var suppressGoAhead = true
    private var doSuppressGaRequested = false
    private var willSuppressGaRequested = false

    /* Telnet command processor state */
    private var mInTelnetCommand = false
    private var mTelnetCommand = 0
    private var mMultipleParameters = false
    private var mLastInputByteProcessed = 0

    /**
     * Process data before sending it to the server.
     * We replace all occurrences of \r with \r\n, as required by the
     * Telnet protocol (CR meant to be a newline should be sent as CR LF,
     * and all other CRs must be sent as CR NUL).
     */
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        // Count the number of CRs
        var numCRs = 0
        for (i in offset until offset + count) {
            if (bytes[i] == '\r'.toByte()) {
                ++numCRs
            }
        }
        if (numCRs == 0) {
            // No CRs -- just send data as-is
            doWrite(bytes, offset, count)
            if (isRunning && !peerEchoesInput) {
                doLocalEcho(bytes)
            }
            return
        }

        // Convert CRs into CRLFs
        val translated = ByteArray(count + numCRs)
        var j = 0
        for (i in offset until offset + count) {
            if (bytes[i] == '\r'.toByte()) {
                translated[j++] = '\r'.toByte()
                translated[j++] = '\n'.toByte()
            } else {
                translated[j++] = bytes[i]
            }
        }

        // Send the data
        doWrite(translated, 0, translated.size)

        // If server echo is off, echo the entered characters locally
        if (isRunning && !peerEchoesInput) {
            doLocalEcho(translated)
        }
    }

    private val mWriteBuf = ByteArray(4096)
    private var mWriteBufLen = 0

    /* Send data to the server, buffering it first if necessary */
    private fun doWrite(data: ByteArray, offset: Int, count: Int) {
        if (peerSuppressedGoAhead) {
            // No need to buffer -- send it straight to the server
            super.write(data, offset, count)
            return
        }

        /* Flush the buffer if it's full ... not strictly correct, but better
           than the alternatives */
        val buffer = mWriteBuf
        var bufLen = mWriteBufLen
        if (bufLen + count > buffer.size) {
            flushWriteBuf()
            bufLen = 0
        }

        // Queue the data to be sent at the next server GA
        System.arraycopy(data, offset, buffer, bufLen, count)
        mWriteBufLen += count
    }

    /* Flush the buffer of data to be written to the server */
    private fun flushWriteBuf() {
        super.write(mWriteBuf, 0, mWriteBufLen)
        mWriteBufLen = 0
    }

    /* Echoes local input from the emulator back to the emulator screen. */
    private fun doLocalEcho(data: ByteArray) {
        if (DEBUG) {
            Log.d(TAG, "echoing " +
                    data.contentToString() + " back to terminal")
        }
        appendToEmulator(data, 0, data.size)
        notifyUpdate()
    }

    /**
     * Input filter which handles Telnet commands and copies data to the
     * terminal emulator.
     */
    public override fun processInput(buffer: ByteArray, offset: Int, count: Int) {
        var lastByte = mLastInputByteProcessed
        for (i in offset until offset + count) {
            // need to interpret the byte as unsigned -- thanks Java!
            val curByte = buffer[i].toInt() and 0xff
            if (DEBUG) {
                Log.d(TAG, "input byte $curByte")
            }
            if (mInTelnetCommand) {
                // Previous byte was part of a command sequence
                doTelnetCommand(curByte)
                lastByte = curByte
                continue
            }
            when (curByte) {
                IAC -> {
                    mInTelnetCommand = true
                    /* Assume we're talking to a real Telnet server */if (!peerIsTelnetd) {
                        doTelnetInit()
                    }
                }
                CMD_GA -> {
                    val cmdGa = byteArrayOf(IAC.toByte(), CMD_GA.toByte())
                    if (!peerSuppressedGoAhead) {
                        if (!suppressGoAhead) {
                            doWrite(cmdGa, 0, cmdGa.size)
                        }
                        flushWriteBuf()
                    }
                }
                0 -> {
                    if (lastByte == '\r'.toInt()) {
                        if (echoInput) {
                            // We do need to echo it back to the server, though
                            doEchoInput(0)
                        }
                        break
                    }
                    /* Send the data to the terminal emulator, and echo it back
                   across the network if the other end wants us to do so. */super.processInput(buffer, i, 1)
                    if (echoInput) {
                        doEchoInput(buffer[i])
                    }
                }
                else -> {
                    super.processInput(buffer, i, 1)
                    if (echoInput) {
                        doEchoInput(buffer[i])
                    }
                }
            }
            lastByte = curByte
        }

        // Save the last byte processed -- we may need it
        mLastInputByteProcessed = lastByte
    }

    private var mOneByte = ByteArray(1)
    private fun doEchoInput(input: Byte) {
        if (DEBUG) {
            Log.d(TAG, "echoing $input to remote end")
        }
        val oneByte = mOneByte
        oneByte[0] = input
        super.write(oneByte, 0, 1)
    }

    /**
     * Interpreter for Telnet commands.
     */
    private fun doTelnetCommand(curByte: Int) {
        /* Handle parameter lists */
        if (mMultipleParameters) {
            if (curByte == CMD_SE) { // SE -- end of parameters
                doMultiParamCommand()
                finishTelnetCommand()
                return
            }
            addMultiParam()
            return
        }
        when (mTelnetCommand) {
            CMD_WILL -> {
                handleWillOption(curByte)
                return
            }
            CMD_WONT -> {
                handleWontOption(curByte)
                return
            }
            CMD_DO -> {
                handleDoOption(curByte)
                return
            }
            CMD_DONT -> {
                handleDontOption(curByte)
                return
            }
        }
        when (curByte) {
            CMD_EC -> {
                // ESC [ D (VT100 cursor left)
                val cmdLeft = byteArrayOf(27.toByte(), '['.toByte(), 'D'.toByte())
                // ESC [ P (VT100 erase char at cursor)
                val cmdErase = byteArrayOf(27.toByte(), '['.toByte(), 'P'.toByte())
                super.processInput(cmdLeft, 0, cmdLeft.size)
                super.processInput(cmdErase, 0, cmdErase.size)
            }
            CMD_EL -> {
                // ESC [ 2 K (VT100 clear whole line)
                val cmdEl = byteArrayOf(27.toByte(), '['.toByte(), '2'.toByte(), 'K'.toByte())
                super.processInput(cmdEl, 0, cmdEl.size)
            }
            IAC -> {
                val iac = byteArrayOf(IAC.toByte())
                super.processInput(iac, 0, iac.size)
            }
            CMD_SB -> {
                mMultipleParameters = true
                return
            }
            CMD_WILL, CMD_WONT, CMD_DO, CMD_DONT -> {
                // Option negotiation -- save the command and wait for the option
                mTelnetCommand = curByte
                return
            }
            CMD_AYT -> {
                val msg = "yes, I'm here\r\n".toByteArray()
                super.write(msg, 0, msg.size)
            }
            CMD_MARK, CMD_BRK, CMD_IP, CMD_AO, CMD_NOP -> {
            }
            else -> {
            }
        }
        finishTelnetCommand()
    }

    // end of command, process next byte normally
    private fun finishTelnetCommand() {
        mTelnetCommand = 0
        mInTelnetCommand = false
        mMultipleParameters = false
    }

    private fun addMultiParam() {
        // unimplemented
    }

    private fun doMultiParamCommand() {
        // unimplemented
    }

    /**
     * Telnet option negotiation code.
     *
     * Because the Telnet protocol is defined to be mostly symmetric with
     * respect to the client and server roles, option negotiation can be
     * somewhat confusing.  The same commands are used to initiate and
     * respond to negotiation requests, and their exact meaning depends on
     * whether they were sent as an initial request or as a response:
     *
     * - WILL:  If sent as a request, indicates that we wish to enable the
     * option on our end.  If sent as a response, indicates that we
     * have enabled the specified option on our end.
     * - WON'T: If sent as a request, indicates that we insist on disabling the
     * option on our end.  If sent as a response, indicates that we
     * refuse to enable the specified option on our end.
     * - DO:    If sent as a request, indicates that we wish the peer to enable
     * this option on the remote end.  If sent as a response, indicates
     * that we accept the peer's request to enable the option on the
     * remote end.
     * - DON'T: If sent as a request, indicates that we demand the peer disable
     * this option on the remote end.  If sent as a response, indicates
     * that we refuse to allow the peer to enable this option on the
     * remote end.
     *
     * All options are off by default (options have to be explicitly requested).
     * In order to prevent negotiation loops, we are not supposed to reply to
     * requests which do not change the state of an option (e.g. if the server
     * sends DON'T ECHO and we're not echoing back what the server sends us, we
     * should not reply with WON'T ECHO).
     *
     * Examples:
     *
     * - server sends WILL ECHO, we reply DO ECHO: the server asks, and we
     * agree, that the server echo the input we send to it back to us over
     * the network.
     * - we send WON'T ECHO, server replies DON'T ECHO: we ask, and the server
     * agrees, that we not echo the input we receive from the server back to
     * the server over the network.
     * - we send DO SUPPRESS-GO-AHEAD, server replies WILL SUPPRESS-GO-AHEAD:
     * we ask, and the server agrees, that the server not send GA to indicate
     * when it's ready to take data (in other words, we can freely send data
     * to the server).
     * - server sends DO ECHO, we reply WON'T ECHO: the server asks us to
     * echo the input we receive from it back over the network, but we refuse
     * to do so.
     */
    private fun handleWillOption(curByte: Int) {
        when (curByte) {
            OPTION_ECHO -> {
                // We don't ever request DO ECHO, so this must be a request
                if (!peerEchoesInput) {
                    sendOption(CMD_DO, OPTION_ECHO)
                }
                peerEchoesInput = true
            }
            OPTION_SUPPRESS_GO_AHEAD -> {
                if (!doSuppressGaRequested && !peerSuppressedGoAhead) {
                    // This is a request which changes our state, send a reply
                    sendOption(CMD_DO, OPTION_SUPPRESS_GO_AHEAD)
                }
                peerSuppressedGoAhead = true
                doSuppressGaRequested = false
                // Flush unwritten data in the output buffer
                flushWriteBuf()
            }
            else ->             // refuse to let other end enable unknown options
                sendOption(CMD_DONT, curByte)
        }
        finishTelnetCommand()
    }

    private fun handleWontOption(curByte: Int) {
        when (curByte) {
            OPTION_ECHO -> {
                // We don't ever request DO ECHO, so this must be a request
                if (peerEchoesInput) {
                    sendOption(CMD_DONT, OPTION_ECHO)
                }
                peerEchoesInput = false
            }
            OPTION_SUPPRESS_GO_AHEAD -> {
                if (!doSuppressGaRequested && peerSuppressedGoAhead) {
                    // This is a request which changes our state, send a reply
                    sendOption(CMD_DONT, OPTION_SUPPRESS_GO_AHEAD)
                }
                peerSuppressedGoAhead = false
                doSuppressGaRequested = false
            }
            else -> {
            }
        }
        finishTelnetCommand()
    }

    private fun handleDoOption(curByte: Int) {
        when (curByte) {
            OPTION_ECHO ->             /* Other Telnet clients like netkit-telnet refuse this request when
               they receive it, since it doesn't make much sense */sendOption(CMD_WONT, OPTION_ECHO)
            OPTION_SUPPRESS_GO_AHEAD -> {
                if (!willSuppressGaRequested && !suppressGoAhead) {
                    // This is a request which changes our state, send a reply
                    sendOption(CMD_WILL, OPTION_SUPPRESS_GO_AHEAD)
                }
                suppressGoAhead = true
                willSuppressGaRequested = false
            }
            else ->             // refuse to enable unknown options
                sendOption(CMD_WONT, curByte)
        }
        finishTelnetCommand()
    }

    private fun handleDontOption(curByte: Int) {
        when (curByte) {
            OPTION_ECHO -> {
                // We don't ever request DON'T ECHO, so this must be a request
                if (echoInput) {
                    sendOption(CMD_WONT, OPTION_ECHO)
                }
                echoInput = false
            }
            OPTION_SUPPRESS_GO_AHEAD -> {
                if (!willSuppressGaRequested && suppressGoAhead) {
                    // This is a request which changes our state, send a reply
                    sendOption(CMD_WONT, curByte)
                }
                suppressGoAhead = false
                willSuppressGaRequested = false
            }
            else -> {
            }
        }
        finishTelnetCommand()
    }

    /* Send an option negotiation command */
    private fun sendOption(command: Int, opt: Int) {
        if (DEBUG) {
            Log.d(TAG, "sending command: $command $opt")
        }
        // option negotiation needs to bypass the write buffer
        val buffer = byteArrayOf(IAC.toByte(), command.toByte(), opt.toByte())
        super.write(buffer, 0, buffer.size)
    }

    private fun requestDoSuppressGoAhead() {
        doSuppressGaRequested = true
        // send IAC DO SUPPRESS-GO-AHEAD
        sendOption(CMD_DO, OPTION_SUPPRESS_GO_AHEAD)
    }

    private fun requestWillSuppressGoAhead() {
        willSuppressGaRequested = true
        // send IAC WILL SUPPRESS-GO-AHEAD
        sendOption(CMD_WILL, OPTION_SUPPRESS_GO_AHEAD)
    }

    /**
     * Called the first time processInput() encounters IAC in the input stream,
     * which is a reasonably good heuristic to determine that the other end is
     * a true Telnet server and not some SMTP/POP/IMAP/whatever server.
     *
     * When called, disables the SUPPRESS-GO-AHEAD option for both directions
     * (required by the standard, but very inconvenient when talking to
     * non-Telnet servers) and sends requests to reenable it in both directions
     * (because it's much easier for us when it's on).
     */
    private fun doTelnetInit() {
        peerSuppressedGoAhead = false
        suppressGoAhead = false
        requestDoSuppressGoAhead()
        requestWillSuppressGoAhead()
        peerIsTelnetd = true
    }

    companion object {
        private const val TAG = "TelnetSession"
        private const val DEBUG = false
        const val IAC = 255
        const val CMD_SE = 240 // SE -- end of parameters
        const val CMD_NOP = 241 // NOP
        const val CMD_MARK = 242 // data mark
        const val CMD_BRK = 243 // send BREAK to terminal
        const val CMD_IP = 244 // Interrupt Process
        const val CMD_AO = 245 // Abort Output
        const val CMD_AYT = 246 // Are You There
        const val CMD_EC = 247 // Erase Character
        const val CMD_EL = 248 // Erase Line
        const val CMD_GA = 249 // Go Ahead (clear to send)
        const val CMD_SB = 250 // SB -- begin parameters
        const val CMD_WILL = 251 // used in option negotiation
        const val CMD_WONT = 252 // used in option negotiation
        const val CMD_DO = 253 // used in option negotiation
        const val CMD_DONT = 254 // used in option negotiation
        const val OPTION_ECHO = 1 // see RFC 857
        const val OPTION_SUPPRESS_GO_AHEAD = 3 // see RFC 858
    }

    /**
     * Create a TelnetSession to handle the telnet protocol and terminal
     * emulation, using an existing InputStream and OutputStream.
     */
    init {
        setTermIn(termIn)
        setTermOut(termOut)
    }
}