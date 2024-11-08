package space.safeyou.ws

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import com.google.android.material.button.MaterialButton
import org.json.JSONException
import org.json.JSONObject
import space.safeyou.ws.WSClient.WSOptions
import space.safeyou.ws.WSClient.WSPacket


@SuppressLint("MissingInflatedId", "ClickableViewAccessibility", "SetTextI18n")
class MainActivity : AppCompatActivity(), WSClient.WSEvents {
    private var TAG: String = "DemoMainActivity"

    private lateinit var vibro: Vibrator
    private lateinit var helpButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var pingTimeView: TextView
    private lateinit var infoView: TextView
    private lateinit var urlInput: EditText
    private lateinit var countryInput: EditText
    private var client: WSClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibro = getSystemService(VIBRATOR_SERVICE) as Vibrator

        pingTimeView = findViewById(R.id.ping_time_view)
        infoView = findViewById(R.id.info_view)
        urlInput = findViewById(R.id.url_input)
        countryInput = findViewById(R.id.country_input)

        statusView = findViewById(R.id.status_view)
        statusView.text = "WS-Disconnected"
        statusView.setTextColor(Color.RED)

        helpButton = findViewById(R.id.help_me_button)
        helpButton.setOnLongClickListener(this::onLongPressHelpME)
        helpButton.isEnabled = false

        connectButton = findViewById(R.id.connect_button)
        connectButton.setOnClickListener(this::onPressConnect)
    }

    private fun onPressConnect(view: View) {
        connectButton.isEnabled = false
        helpButton.isEnabled = true

        client?.disconnect(0, "")

        // Create WebSocket client options
        val options = WSOptions.init()
            .setUrl(urlInput.text.toString()) // URL to connect to the WebSocket server with an access token
            .setProtocol("SafeYOU") // Protocol to use for WebSocket communication
            .setDebugMode(true) // Flag to enable debug mode for logging WebSocket client actions
        client = WSClient(options, this)

        client?.connect()
    }

    private fun onLongPressHelpME(view: View): Boolean {
        // Example JSON data representing a help request

        val data = JSONObject()
            .put("type", "help_request")
            .put(
                "data", JSONObject()
                    .put("coordinates", "40.7657796,43.8338588")
                    .put("address", "Lalayan St, Gyumri, Armenia")
                    .put("message", "Please help me!!!")
                    .put("country_code", countryInput.text.toString())
                    .put("language_code", "en")
            )

        // This method sends JSON data to the server.
        client?.sendPacket(data)

        // This method sends JSON data along with file content as bytes to the server.
        // client.sendPacket(data, "YOUR FILE CONTENT".toByteArray())

        vibro.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

        return true
    }

    // MARK: WS Events

    override fun onConnecting(client: WSClient?) {
        // Called when attempting to connect
        // Add logic here that executes when a connection attempt starts
        setStatusText("WS-Connecting", Color.YELLOW)
    }

    override fun onConnected(client: WSClient, id: String?) {
        // Called when successfully connected to the server
        setStatusText("WS-Connected\n${client.clientSID}", Color.GREEN)
        setInfo(client.clientInfo)
    }

    override fun onDisconnected(client: WSClient?, code: Int, reason: String?) {
        // Called when disconnected from the server
        // Add logic here that executes when disconnected from the server
        setStatusText("WS-Disconnected\n[$code] $reason", Color.RED)

        runOnUiThread {
            run {
                helpButton.isEnabled = false
                connectButton.isEnabled = true
            }
        }
    }

    override fun onReceivedPacket(client: WSClient?, packet: WSPacket) {
        // Called when receiving a packet from the server
        if (packet.type == 1) {
            runOnUiThread {
                try {
                    var data = packet.dataAsJSONObject
                    val type = data.getString("type")
                    if (type.equals("dialog.show", ignoreCase = true)) {
                        data = data.getJSONObject("data")

                        val dialogId = data.getInt("id")
                        val dialogType = data.getString("type")
                        val dialogTitle = data.getString("title")
                        val dialogMessage = data.getString("message")

                        if (dialogType.equals("with_actions", ignoreCase = true)) {
                            val positiveActionLabel =
                                data.getString("positive_action_label")
                            val negativeActionLabel =
                                data.getString("negative_action_label")
                            val dialogTimeout = data.getInt("timeout")
                            showDialogWithActions(
                                dialogTimeout.toLong(),
                                dialogTitle,
                                dialogMessage,
                                positiveActionLabel,
                                negativeActionLabel
                            ) { actionName ->
                                try {
                                    client!!.sendPacket(
                                        JSONObject().put("type", "dialog.action_pressed")
                                            .put(
                                                "data",
                                                JSONObject().put("dialog_id", dialogId)
                                                    .put("dialog_action", actionName)
                                            )
                                    )
                                } catch (e: JSONException) {
                                    throw RuntimeException(e)
                                }
                            }
                        } else if (dialogType.equals("closable", ignoreCase = true)) {
                            val closeActionLabel =
                                data.getString("close_action_label")
                            showDialogWithoutActions(dialogTitle, dialogMessage, closeActionLabel)
                        }
                    }
                } catch (ex: java.lang.Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    override fun onError(client: WSClient?, exception: Exception?) {
        // Called when an error occurs
        // Add logic here to handle errors
        Log.d(TAG, "onError: " + exception?.message)


        runOnUiThread {
            run {
                helpButton.isEnabled = false
                connectButton.isEnabled = true
            }
        }
    }

    override fun onPingTime(client: WSClient?, milliseconds: Long) {
        // Called when receiving ping time from the server
        // Add logic here that utilizes the received ping time
        setPingTime(formatMillisecondsToPingTime(milliseconds))
    }

    @SuppressLint("DefaultLocale")
    private fun formatMillisecondsToPingTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val remainingMilliseconds = milliseconds % 1000
        val formattedSeconds = seconds.toString()
        val formattedMilliseconds = String.format("%03d", remainingMilliseconds)
        return "$formattedSeconds.$formattedMilliseconds sec"
    }


    private fun showDialogWithActions(
        timeoutMillis: Long,
        title: String?,
        message: String?,
        positiveButtonLabel: String?,
        negativeButtonLabel: String?,
        callback: Consumer<String?>
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(
            positiveButtonLabel
        ) { _: DialogInterface?, _: Int ->
            callback.accept(
                "positive_action"
            )
        }
        builder.setNegativeButton(
            negativeButtonLabel
        ) { _: DialogInterface?, _: Int ->
            callback.accept(
                "negative_action"
            )
        }
        val dialog = builder.create()
        dialog.show()
        Handler().postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, timeoutMillis)
    }

    private fun showDialogWithoutActions(title: String?, message: String?, positiveButtonLabel: String?) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(
            positiveButtonLabel
        ) { _: DialogInterface?, _: Int -> }
        val dialog = builder.create()
        dialog.show()
    }

    private fun setStatusText(text: String, color: Int) {
        runOnUiThread {
            run {
                statusView.text = text
                statusView.setTextColor(color)
            }
        }
    }

    private fun setPingTime(text: String) {
        runOnUiThread {
            run {
                pingTimeView.text = text
            }
        }
    }

    private fun setInfo(info: JSONObject) {
        runOnUiThread {
            run {
                infoView.text = info.toString(4)
            }
        }
    }
}