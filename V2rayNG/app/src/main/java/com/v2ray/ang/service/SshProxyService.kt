package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class SshProxyService : Service() {

    private val binder = LocalBinder()
    private var sshSession: Session? = null
    private var socksServer: ServerSocket? = null
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false

    // Configuration
    private var localSocksPort = 20808 // Default, can be passed
    private var remoteUdpgwPort = 7300

    // UDPGW Constants
    private val UDPGW_CLIENT_FLAG_KEEPALIVE = 0x01
    private val UDPGW_CLIENT_FLAG_REBIND = 0x02
    private val UDPGW_CLIENT_FLAG_DNS = 0x04
    private val UDPGW_CLIENT_FLAG_IPV6 = 0x08

    companion object {
        const val TAG = "SshProxyService"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USER = "extra_user"
        const val EXTRA_PASS = "extra_pass"
        const val EXTRA_UDPGW_PORT = "extra_udpgw_port"
        const val EXTRA_LOCAL_SOCKS_PORT = "extra_local_socks_port"
        const val NOTIFICATION_ID = 42001
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshProxyService = this@SshProxyService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        val host = intent.getStringExtra(EXTRA_HOST)
        if (!host.isNullOrEmpty()) {
             val port = intent.getStringExtra(EXTRA_PORT) ?: "22"
             val user = intent.getStringExtra(EXTRA_USER) ?: ""
             val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
             remoteUdpgwPort = intent.getIntExtra(EXTRA_UDPGW_PORT, 7300)
             localSocksPort = intent.getIntExtra(EXTRA_LOCAL_SOCKS_PORT, 20808)

             startForegroundService()

             startSsh(host, port, user, pass)
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ssh_service_channel",
                "SSH Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, "ssh_service_channel")
            .setContentTitle("SSH Proxy Running")
            .setContentText("Connected to SSH Server")
            .setSmallIcon(R.drawable.ic_stat_name)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSsh()
    }

    fun startSsh(host: String, portStr: String, user: String, pass: String) {
        if (isRunning) stopSsh() // Restart if already running

        isRunning = true
        job = serviceScope.launch {
            try {
                Log.d(TAG, "Starting SSH Connection to $host:$portStr")
                connectSsh(host, portStr.toIntOrNull() ?: 22, user, pass)
                startSocksServer()
            } catch (e: Exception) {
                Log.e(TAG, "SSH Start Failed", e)
                stopSelf() // Stop service if connection fails
            }
        }
    }

    // Kept for backward compat with Bind calls if needed, but primary is startService
    fun stopSsh() {
        isRunning = false
        job?.cancel()
        try {
            socksServer?.close()
        } catch (e: Exception) { /* ignore */ }
        try {
            sshSession?.disconnect()
        } catch (e: Exception) { /* ignore */ }
        sshSession = null
        socksServer = null
        Log.d(TAG, "SSH Service Stopped")
        stopForeground(true)
    }

    private fun connectSsh(host: String, port: Int, user: String, pass: String) {
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        session.setPassword(pass)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(30000) // 30s timeout
        sshSession = session
        Log.d(TAG, "SSH Connected")
    }

    private fun startSocksServer() {
        try {
            socksServer = ServerSocket(localSocksPort, 50, InetAddress.getByName("127.0.0.1"))
            Log.d(TAG, "SOCKS5 Server listening on $localSocksPort")

            while (isRunning && serviceScope.isActive) {
                try {
                    val clientSocket = socksServer?.accept() ?: break
                    serviceScope.launch {
                        handleSocksClient(clientSocket)
                    }
                } catch (e: IOException) {
                    if (isRunning) Log.e(TAG, "SOCKS Accept Error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS Server Start Failed", e)
            throw e
        }
    }

    private fun handleSocksClient(clientSocket: Socket) {
        clientSocket.use { socket ->
            try {
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // 1. Negotiation
                val ver = input.read()
                if (ver != 5) return

                val nMethods = input.read()
                val methods = ByteArray(nMethods)
                input.readFully(methods)

                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // 2. Request
                val ver2 = input.read()
                val cmd = input.read()
                val rsv = input.read()
                val atyp = input.read()

                if (ver2 != 5) return

                var destAddr = ""
                when (atyp) {
                    1 -> { // IPv4
                        val ip = ByteArray(4)
                        input.readFully(ip)
                        destAddr = InetAddress.getByAddress(ip).hostAddress
                    }
                    3 -> { // Domain name
                        val len = input.read()
                        val domain = ByteArray(len)
                        input.readFully(domain)
                        destAddr = String(domain)
                    }
                    4 -> { // IPv6
                        val ip = ByteArray(16)
                        input.readFully(ip)
                        destAddr = InetAddress.getByAddress(ip).hostAddress
                    }
                    else -> return
                }

                val portBytes = ByteArray(2)
                input.readFully(portBytes)
                val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                when (cmd) {
                    1 -> { // CONNECT (TCP)
                        handleTcpConnect(socket, destAddr, destPort, output)
                    }
                    3 -> { // UDP ASSOCIATE
                        handleUdpAssociate(socket, output)
                    }
                    else -> {
                        output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "SOCKS Handler Error", e)
            }
        }
    }

    private fun handleTcpConnect(clientSocket: Socket, destHost: String, destPort: Int, output: DataOutputStream) {
        val session = sshSession ?: return
        if (!session.isConnected) return

        try {
            val channel = session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.connect(10000)

            if (channel.isConnected) {
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()
                val remoteIn = channel.inputStream
                val remoteOut = channel.outputStream

                val t1 = serviceScope.launch {
                    try {
                        clientIn.copyTo(remoteOut)
                    } catch (e: Exception) { /* ignore */ }
                    finally { channel.disconnect(); try{ clientSocket.close() }catch(e:Exception){} }
                }
                val t2 = serviceScope.launch {
                    try {
                        remoteIn.copyTo(clientOut)
                    } catch (e: Exception) { /* ignore */ }
                    finally { try{ clientSocket.close() }catch(e:Exception){} }
                }
            } else {
                 output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                 output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP Connect Failed: $destHost:$destPort", e)
            try {
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            } catch (ignore: Exception) {}
        }
    }

    private fun handleUdpAssociate(clientSocket: Socket, output: DataOutputStream) {
        val session = sshSession ?: return
        if (!session.isConnected) return

        val udpRelaySocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val relayPort = udpRelaySocket.localPort

        try {
            val channel = session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
            channel.setHost("127.0.0.1")
            channel.setPort(remoteUdpgwPort)
            channel.connect(10000)

            if (!channel.isConnected) {
                throw IOException("Could not connect to remote udpgw")
            }

            val ipBytes = InetAddress.getByName("127.0.0.1").address
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01))
            output.write(ipBytes)
            output.write(byteArrayOf(((relayPort ushr 8) and 0xFF).toByte(), (relayPort and 0xFF).toByte()))
            output.flush()

            val udpgwIn = channel.inputStream
            val udpgwOut = channel.outputStream

            val conIdMap = ConcurrentHashMap<String, Int>()
            val revConIdMap = ConcurrentHashMap<Int, SOCKS_Header>()
            var nextConId = 1

            // Receive from UDP Socket (Client -> Relay)
            serviceScope.launch {
                val buffer = ByteArray(65535)
                while (isActive && !udpRelaySocket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpRelaySocket.receive(packet)

                        val currentClientAddr = packet.address
                        val currentClientPort = packet.port

                        val data = packet.data
                        val len = packet.length
                        if (len < 10) continue

                        val frag = data[2].toInt()
                        if (frag != 0) continue

                        val atyp = data[3].toInt()
                        var headerLen = 0
                        var destIpStr = ""
                        var destPort = 0

                        var pos = 4
                        when (atyp) {
                            1 -> {
                                val ipBytes = ByteArray(4)
                                System.arraycopy(data, pos, ipBytes, 0, 4)
                                destIpStr = InetAddress.getByAddress(ipBytes).hostAddress
                                pos += 4
                                headerLen = 4 + 4 + 2
                            }
                            3 -> {
                                val dLen = data[pos].toInt()
                                pos++
                                val dBytes = ByteArray(dLen)
                                System.arraycopy(data, pos, dBytes, 0, dLen)
                                destIpStr = String(dBytes)
                                pos += dLen
                                headerLen = 4 + 1 + dLen + 2
                            }
                            4 -> {
                                val ipBytes = ByteArray(16)
                                System.arraycopy(data, pos, ipBytes, 0, 16)
                                destIpStr = InetAddress.getByAddress(ipBytes).hostAddress
                                pos += 16
                                headerLen = 4 + 16 + 2
                            }
                            else -> continue
                        }

                        val p1 = data[pos].toInt() and 0xFF
                        val p2 = data[pos+1].toInt() and 0xFF
                        destPort = (p1 shl 8) or p2

                        val payloadLen = len - headerLen
                        val payload = ByteArray(payloadLen)
                        System.arraycopy(data, headerLen, payload, 0, payloadLen)

                        val key = "$destIpStr:$destPort"
                        var conId = conIdMap[key]
                        if (conId == null) {
                            conId = nextConId++
                            if (nextConId > 65535) nextConId = 1
                            conIdMap[key] = conId
                            revConIdMap[conId] = SOCKS_Header(atyp, destIpStr, destPort, currentClientAddr, currentClientPort)
                        } else {
                            // Update client addr/port if changed (NAT?)
                            val header = revConIdMap[conId]
                            if (header != null && (header.clientAddr != currentClientAddr || header.clientPort != currentClientPort)) {
                                revConIdMap[conId] = header.copy(clientAddr = currentClientAddr, clientPort = currentClientPort)
                            }
                        }

                        val isIpv6 = (atyp == 4)
                        var flags = 0
                        if (isIpv6) flags = flags or UDPGW_CLIENT_FLAG_IPV6

                        val udpgwBuffer = java.io.ByteArrayOutputStream()
                        udpgwBuffer.write(flags)
                        udpgwBuffer.write((conId shr 8) and 0xFF)
                        udpgwBuffer.write(conId and 0xFF)

                        val addrIp = InetAddress.getByName(destIpStr)
                        udpgwBuffer.write(addrIp.address)
                        udpgwBuffer.write((destPort shr 8) and 0xFF)
                        udpgwBuffer.write(destPort and 0xFF)

                        udpgwBuffer.write(payload)

                        val udpgwPacket = udpgwBuffer.toByteArray()

                        val packetLen = udpgwPacket.size
                        synchronized(udpgwOut) {
                             udpgwOut.write(packetLen and 0xFF)
                             udpgwOut.write((packetLen shr 8) and 0xFF)
                             udpgwOut.write(udpgwPacket)
                             udpgwOut.flush()
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "UDP Relay Send Error", e)
                    }
                }
            }

            // Receive from SSH (UDPGW -> Relay -> Client)
            serviceScope.launch {
                val headerBuf = ByteArray(2)
                while (isActive && channel.isConnected) {
                    try {
                        var read = 0
                        while (read < 2) {
                            val r = udpgwIn.read(headerBuf, read, 2 - read)
                            if (r < 0) throw IOException("EOF")
                            read += r
                        }
                        val len = (headerBuf[0].toInt() and 0xFF) or ((headerBuf[1].toInt() and 0xFF) shl 8)

                        val packetBuf = ByteArray(len)
                        read = 0
                        while (read < len) {
                            val r = udpgwIn.read(packetBuf, read, len - read)
                            if (r < 0) throw IOException("EOF")
                            read += r
                        }

                        if (len < 3) continue
                        val flags = packetBuf[0].toInt()
                        val conId = ((packetBuf[1].toInt() and 0xFF) shl 8) or (packetBuf[2].toInt() and 0xFF)

                        val isIpv6 = (flags and UDPGW_CLIENT_FLAG_IPV6) != 0

                        val addrLen = if (isIpv6) 16+2 else 4+2
                        if (len < 3 + addrLen) continue

                        var pos = 3
                        val srcIpBytes = ByteArray(if (isIpv6) 16 else 4)
                        System.arraycopy(packetBuf, pos, srcIpBytes, 0, srcIpBytes.size)
                        pos += srcIpBytes.size

                        val srcPortBytes = ByteArray(2)
                        System.arraycopy(packetBuf, pos, srcPortBytes, 0, 2)
                        pos += 2

                        val payloadSize = len - pos
                        val payload = ByteArray(payloadSize)
                        System.arraycopy(packetBuf, pos, payload, 0, payloadSize)

                        val socksBuf = java.io.ByteArrayOutputStream()
                        socksBuf.write(byteArrayOf(0, 0, 0))

                        if (isIpv6) {
                            socksBuf.write(4)
                            socksBuf.write(srcIpBytes)
                        } else {
                            socksBuf.write(1)
                            socksBuf.write(srcIpBytes)
                        }
                        socksBuf.write(srcPortBytes)
                        socksBuf.write(payload)

                        val socksPacket = socksBuf.toByteArray()

                        val header = revConIdMap[conId]
                        if (header != null) {
                             val dp = DatagramPacket(socksPacket, socksPacket.size, header.clientAddr, header.clientPort)
                             udpRelaySocket.send(dp)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "UDP Relay Recv Error", e)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDPGW Setup Failed", e)
            try {
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            } catch(ignore: Exception) {}
        }
    }

    data class SOCKS_Header(val atyp: Int, val destStr: String, val destPort: Int, val clientAddr: InetAddress, val clientPort: Int)
}
