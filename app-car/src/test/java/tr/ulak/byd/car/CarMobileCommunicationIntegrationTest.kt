package tr.ulak.byd.car

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CarMobileCommunicationIntegrationTest {

    @Test
    fun testBidirectionalCarMobileWithAck(): Unit = runBlocking {
        val bridge = DiLink3HardwareBridge()
        val serverSocket = ServerSocket(0)
        val tcpPort = serverSocket.localPort

        val tcpServerJob = launch(Dispatchers.IO) {
            val client = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
            val line = reader.readLine()
            if (!line.isNullOrBlank()) {
                val cmd = JSONObject(line)
                val res = bridge.executeRemoteCommand(cmd)
                writer.write(
                    JSONObject()
                        .put("ok", res.success)
                        .put("hardware_executed", res.hardwareExecuted)
                        .put("message", res.message)
                        .toString() + "\n",
                )
                writer.flush()
            }
            client.close()
            serverSocket.close()
        }

        val udpSocket = DatagramSocket(0)
        val udpPort = udpSocket.localPort
        val receivedTelemetry = CompletableDeferred<JSONObject>()

        val udpJob = launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            val pkt = DatagramPacket(buf, buf.size)
            udpSocket.receive(pkt)
            receivedTelemetry.complete(JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8)))
            udpSocket.close()
        }

        delay(150)

        val snap = bridge.readTelemetrySnapshot()
        val sendSocket = DatagramSocket()
        val bytes = snap.toString().toByteArray(Charsets.UTF_8)
        sendSocket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), udpPort))
        sendSocket.close()

        val telemetry = withTimeout(2000.milliseconds) { receivedTelemetry.await() }
        assertEquals("ULAK", telemetry.getString("app"))
        assertEquals("3.0.0", telemetry.getString("version"))

        delay(150)

        val clientSocket = Socket("127.0.0.1", tcpPort)
        val writer = OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.UTF_8))
        writer.write(JSONObject().put("command", "SET_AC").put("enable", true).put("temp", 22.5).toString() + "\n")
        writer.flush()
        val ackJson = JSONObject(reader.readLine())
        clientSocket.close()

        assertTrue(ackJson.getBoolean("ok"))
        assertTrue(bridge.acActiveFallback)
        assertEquals(22.5, bridge.targetTempFallback, 0.01)

        joinAll(tcpServerJob, udpJob)
    }
}
