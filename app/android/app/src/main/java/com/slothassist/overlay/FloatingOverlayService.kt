package com.slothassist.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.slothassist.MainActivity
import com.slothassist.automation.TaskRunner
import com.slothassist.automation.TaskStatus
import kotlin.math.abs

/**
 * Draws the sloth bubble and the command panel above whatever app is open.
 * Lives in native code so it survives the React Native runtime being backgrounded.
 */
class FloatingOverlayService : Service() {

  private lateinit var wm: WindowManager
  private lateinit var bubble: TextView
  private lateinit var bubbleParams: WindowManager.LayoutParams
  private lateinit var panel: LinearLayout
  private lateinit var panelParams: WindowManager.LayoutParams
  private lateinit var input: EditText
  private lateinit var statusView: TextView
  private lateinit var pill: TextView
  private lateinit var pillParams: WindowManager.LayoutParams
  private var panelShown = false
  private var pillShown = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    if (!Settings.canDrawOverlays(this)) {
      stopSelf()
      return
    }
    startInForeground()
    wm = getSystemService(WINDOW_SERVICE) as WindowManager
    buildBubble()
    buildPanel()
    buildPill()
    TaskRunner.listener = ::onStatus
    running = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) stopSelf()
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    TaskRunner.cancel()
    TaskRunner.listener = null
    if (::bubble.isInitialized) runCatching { wm.removeView(bubble) }
    if (panelShown) runCatching { wm.removeView(panel) }
    if (pillShown) runCatching { wm.removeView(pill) }
    super.onDestroy()
  }

  // ---------- bubble ----------

  @SuppressLint("ClickableViewAccessibility")
  private fun buildBubble() {
    val size = dp(56)
    bubble = TextView(this).apply {
      text = "🦥"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
      gravity = Gravity.CENTER
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#F5E6C8"))
        setStroke(dp(3), IDLE_COLOR)
      }
      elevation = dp(6).toFloat()
      contentDescription = "SlothAssist. Tap to give a command, drag to move."
    }
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    bubbleParams = baseParams(size, size, focusable = false).apply {
      gravity = Gravity.TOP or Gravity.START
      x = prefs.getInt("x", 24)
      y = prefs.getInt("y", 400)
    }

    var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
    bubble.setOnTouchListener { _, e ->
      when (e.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; dragging = false
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - downX; val dy = e.rawY - downY
          if (dragging || abs(dx) > dp(8) || abs(dy) > dp(8)) {
            dragging = true
            bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
            wm.updateViewLayout(bubble, bubbleParams)
            if (pillShown) positionPill()
          }
        }
        MotionEvent.ACTION_UP -> {
          if (dragging) prefs.edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply()
          else if (TaskRunner.isRunning) TaskRunner.cancel()
          else togglePanel()
        }
      }
      true
    }
    wm.addView(bubble, bubbleParams)
  }

  private fun setBubbleColor(color: Int) {
    (bubble.background as GradientDrawable).setStroke(dp(3), color)
  }

  // ---------- panel ----------

  private fun buildPanel() {
    panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), dp(12), dp(14), dp(12))
      background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor("#FFFDF7"))
        setStroke(dp(1), Color.parseColor("#E2D6BE"))
      }
      elevation = dp(10).toFloat()
    }

    val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    header.addView(TextView(this).apply {
      text = "🦥  What should I do?"
      setTextColor(Color.parseColor("#3B2F1E"))
      setTypeface(typeface, Typeface.BOLD)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(smallButton("—", "Minimize") { hidePanel() })
    header.addView(smallButton("✕", "Turn off the floating assistant") { stopSelf() })
    panel.addView(header)

    input = EditText(this).apply {
      hint = DEFAULT_HINT
      setSingleLine()
      inputType = InputType.TYPE_CLASS_TEXT
      imeOptions = EditorInfo.IME_ACTION_GO
      setTextColor(Color.parseColor("#3B2F1E"))
      setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
    }
    panel.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    statusView = TextView(this).apply {
      text = "Try: \"search for rice 1 kg and add 2 of the first option to the cart\""
      setTextColor(Color.parseColor("#7A6A52"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      setPadding(0, dp(6), 0, dp(6))
    }
    panel.addView(statusView)

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    actions.addView(Button(this).apply { text = "Stop"; setOnClickListener { TaskRunner.cancel() } })
    actions.addView(Button(this).apply { text = "Go"; setOnClickListener { submit() } })
    panel.addView(actions)

    val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
    panelParams = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      y = dp(80)
    }
  }

  // ---------- status pill (shown while a task runs) ----------

  private fun buildPill() {
    pill = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      setPadding(dp(12), dp(8), dp(12), dp(8))
      maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
      background = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.parseColor("#E63B2F1E"))
      }
    }
    pillParams = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false).apply {
      gravity = Gravity.TOP or Gravity.START
      flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
  }

  private fun positionPill() {
    pillParams.x = bubbleParams.x
    pillParams.y = bubbleParams.y + dp(62)
    wm.updateViewLayout(pill, pillParams)
  }

  private fun showPill(text: String) {
    pill.text = "$text\n(tap 🦥 to stop)"
    if (!pillShown) {
      pillParams.x = bubbleParams.x
      pillParams.y = bubbleParams.y + dp(62)
      wm.addView(pill, pillParams)
      pillShown = true
    }
  }

  private fun hidePill() {
    if (pillShown) wm.removeView(pill)
    pillShown = false
  }

  private fun togglePanel() = if (panelShown) hidePanel() else showPanel()

  private fun showPanel() {
    if (panelShown) return
    setPanelFocusable(true)
    wm.addView(panel, panelParams)
    panelShown = true
    input.requestFocus()
  }

  private fun hidePanel() {
    if (!panelShown) return
    hideKeyboard()
    wm.removeView(panel)
    panelShown = false
  }

  private fun submit() {
    val command = input.text.toString().trim()
    if (command.isEmpty()) return
    input.setText("")
    // Get out of the way: hand focus back to the target app and stop covering what the agent taps.
    hidePanel()
    bubble.postDelayed({ TaskRunner.run(command) }, 350)
  }

  private fun onStatus(s: TaskStatus) {
    statusView.text = s.message
    val (ring, textColor) = when (s.state) {
      TaskStatus.State.RUNNING -> RUNNING_COLOR to Color.parseColor("#3B2F1E")
      TaskStatus.State.DONE -> DONE_COLOR to Color.parseColor("#2E7D32")
      TaskStatus.State.FAILED -> FAIL_COLOR to Color.parseColor("#B3261E")
      TaskStatus.State.NEEDS_USER -> ASK_COLOR to Color.parseColor("#1F4E99")
      TaskStatus.State.IDLE -> IDLE_COLOR to Color.parseColor("#7A6A52")
    }
    setBubbleColor(ring)
    statusView.setTextColor(textColor)

    if (s.state == TaskStatus.State.RUNNING) {
      showPill(s.message)
      return
    }
    hidePill()
    input.hint = if (s.state == TaskStatus.State.NEEDS_USER) "Your answer…" else DEFAULT_HINT
    showPanel()
    bubble.postDelayed({ if (!TaskRunner.isRunning && !TaskRunner.awaitingAnswer) setBubbleColor(IDLE_COLOR) }, 4000)
  }

  private fun setPanelFocusable(focusable: Boolean) {
    panelParams.flags = flagsFor(focusable)
    if (panelShown) wm.updateViewLayout(panel, panelParams)
  }

  private fun hideKeyboard() {
    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
  }

  // ---------- plumbing ----------

  private fun smallButton(label: String, description: String, onClick: () -> Unit) = TextView(this).apply {
    text = label
    contentDescription = description
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTextColor(Color.parseColor("#7A6A52"))
    gravity = Gravity.CENTER
    minWidth = dp(44); minHeight = dp(44)
    setOnClickListener { onClick() }
  }

  private fun baseParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
    w, h,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
    flagsFor(focusable),
    PixelFormat.TRANSLUCENT,
  )

  /** Focusable only while the user is typing; touches outside always reach the app underneath. */
  private fun flagsFor(focusable: Boolean): Int {
    var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!focusable) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    return f
  }

  private fun startInForeground() {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating assistant", NotificationManager.IMPORTANCE_LOW))
    }
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, 1, Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
    else @Suppress("DEPRECATION") Notification.Builder(this)
    val notification = builder
      .setSmallIcon(android.R.drawable.ic_menu_search)
      .setContentTitle("SlothAssist is floating")
      .setContentText("Tap the sloth in any app to give a command.")
      .setContentIntent(open)
      .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
      .setOngoing(true)
      .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  companion object {
    private const val CHANNEL = "floating"
    private const val NOTIFICATION_ID = 7
    private const val PREFS = "bubble"
    private const val ACTION_STOP = "com.slothassist.STOP_BUBBLE"
    private val IDLE_COLOR = Color.parseColor("#8D6E4A")
    private val RUNNING_COLOR = Color.parseColor("#F2A900")
    private val DONE_COLOR = Color.parseColor("#2E7D32")
    private val FAIL_COLOR = Color.parseColor("#B3261E")
    private val ASK_COLOR = Color.parseColor("#1F6FEB")
    private const val DEFAULT_HINT = "e.g. search for rice 1 kg and add 2 to cart"

    @Volatile var running = false
      private set

    fun start(ctx: Context) {
      val i = Intent(ctx, FloatingOverlayService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, FloatingOverlayService::class.java))
    }
  }
}
