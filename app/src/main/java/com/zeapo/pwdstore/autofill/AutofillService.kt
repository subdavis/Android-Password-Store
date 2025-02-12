package com.zeapo.pwdstore.autofill

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.utils.PasswordRepository
import com.zeapo.pwdstore.utils.splitLines
import org.apache.commons.io.FileUtils
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.util.ArrayList
import java.util.Locale

class AutofillService : AccessibilityService() {
    private var serviceConnection: OpenPgpServiceConnection? = null
    private var settings: SharedPreferences? = null
    private var info: AccessibilityNodeInfo? = null // the original source of the event (the edittext field)
    private var items: ArrayList<File> = arrayListOf() // password choices
    private var lastWhichItem: Int = 0
    private var dialog: AlertDialog? = null
    private var window: AccessibilityWindowInfo? = null
    private var resultData: Intent? = null // need the intent which contains results from user interaction
    private var packageName: CharSequence? = null
    private var ignoreActionFocus = false
    private var webViewTitle: String? = null
    private var webViewURL: String? = null
    private var lastPassword: PasswordEntry? = null
    private var lastPasswordMaxDate: Long = 0

    fun setResultData(data: Intent) {
        resultData = data
    }

    fun setPickedPassword(path: String) {
        items.add(File("${PasswordRepository.getRepositoryDirectory(applicationContext)}/$path.gpg"))
        bindDecryptAndVerify()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnection = OpenPgpServiceConnection(this@AutofillService, "org.sufficientlysecure.keychain")
        serviceConnection!!.bindToService()
        settings = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // remove stored password from cache
        if (lastPassword != null && System.currentTimeMillis() > lastPasswordMaxDate) {
            lastPassword = null
        }

        // if returning to the source app from a successful AutofillActivity
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.packageName != null && event.packageName == packageName
                && resultData != null) {
            bindDecryptAndVerify()
        }

        // look for webView and trigger accessibility events if window changes
        // or if page changes in chrome
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        && event.packageName != null
                        && (event.packageName == "com.android.chrome" || event.packageName == "com.android.browser"))) {
            // there is a chance for getRootInActiveWindow() to return null at any time. save it.
            try {
                val root = rootInActiveWindow
                webViewTitle = searchWebView(root)
                webViewURL = null
                if (webViewTitle != null) {
                    var nodes = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                    if (nodes.isEmpty()) {
                        nodes = root.findAccessibilityNodeInfosByViewId("com.android.browser:id/url")
                    }
                    for (node in nodes)
                        if (node.text != null) {
                            try {
                                webViewURL = URL(node.text.toString()).host
                            } catch (e: MalformedURLException) {
                                if (e.toString().contains("Protocol not found")) {
                                    try {
                                        webViewURL = URL("http://" + node.text.toString()).host
                                    } catch (ignored: MalformedURLException) {
                                    }

                                }
                            }

                        }
                }
            } catch (e: Exception) {
                // sadly we were unable to access the data we wanted
                return
            }

        }

        // nothing to do if field is keychain app or system ui
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.packageName != null && event.packageName == "org.sufficientlysecure.keychain"
                || event.packageName != null && event.packageName == "com.android.systemui") {
            dismissDialog(event)
            return
        }

        if (!event.isPassword) {
            if (lastPassword != null && event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.source.isEditable) {
                showPasteUsernameDialog(event.source, lastPassword!!)
                return
            } else {
                // nothing to do if not password field focus
                dismissDialog(event)
                return
            }
        }

        if (dialog != null && dialog!!.isShowing) {
            // the current dialog must belong to this window; ignore clicks on this password field
            // why handle clicks at all then? some cases e.g. Paypal there is no initial focus event
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                return
            }
            // if it was not a click, the field was refocused or another field was focused; recreate
            dialog!!.dismiss()
            dialog = null
        }

        // ignore the ACTION_FOCUS from decryptAndVerify otherwise dialog will appear after Fill
        if (ignoreActionFocus) {
            ignoreActionFocus = false
            return
        }

        // need to request permission before attempting to draw dialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        // we are now going to attempt to fill, save AccessibilityNodeInfo for later in decryptAndVerify
        // (there should be a proper way to do this, although this seems to work 90% of the time)
        info = event.source
        if (info == null) return

        // save the dialog's corresponding window so we can use getWindows() in dismissDialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window = info!!.window
        }

        val packageName: String
        val appName: String
        val isWeb: Boolean

        // Match with the app if a webview was not found or one was found but
        // there's no title or url to go by
        if (webViewTitle == null || webViewTitle == "" && webViewURL == null) {
            if (info!!.packageName == null) return
            packageName = info!!.packageName.toString()

            // get the app name and find a corresponding password
            val packageManager = packageManager
            var applicationInfo: ApplicationInfo?
            try {
                applicationInfo = packageManager.getApplicationInfo(event.packageName.toString(), 0)
            } catch (e: PackageManager.NameNotFoundException) {
                applicationInfo = null
            }

            appName = (if (applicationInfo != null) packageManager.getApplicationLabel(applicationInfo) else "").toString()

            isWeb = false

            setAppMatchingPasswords(appName, packageName)
        } else {
            // now we may have found a title but webViewURL could be null
            // we set packagename so that we can find the website setting entry
            packageName = setWebMatchingPasswords(webViewTitle!!, webViewURL)
            appName = packageName
            isWeb = true
        }

        // if autofill_always checked, show dialog even if no matches (automatic
        // or otherwise)
        if (items.isEmpty() && !settings!!.getBoolean("autofill_always", false)) {
            return
        }
        showSelectPasswordDialog(packageName, appName, isWeb)
    }

    private fun searchWebView(source: AccessibilityNodeInfo?, depth: Int = 10): String? {
        if (source == null || depth == 0) {
            return null
        }
        for (i in 0 until source.childCount) {
            val u = source.getChild(i) ?: continue
            if (u.className != null && u.className == "android.webkit.WebView") {
                return if (u.contentDescription != null) {
                    u.contentDescription.toString()
                } else ""
            }
            val webView = searchWebView(u, depth - 1)
            if (webView != null) {
                return webView
            }
            u.recycle()
        }
        return null
    }

    // dismiss the dialog if the window has changed
    private fun dismissDialog(event: AccessibilityEvent) {
        // the default keyboard showing/hiding is a window state changed event
        // on Android 5+ we can use getWindows() to determine when the original window is not visible
        // on Android 4.3 we have to use window state changed events and filter out the keyboard ones
        // there may be other exceptions...
        val dismiss: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            !windows.contains(window)
        } else {
            !(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.packageName != null &&
                    event.packageName.toString().contains("inputmethod"))
        }
        if (dismiss && dialog != null && dialog!!.isShowing) {
            dialog!!.dismiss()
            dialog = null
        }
    }

    private fun setWebMatchingPasswords(webViewTitle: String, webViewURL: String?): String {
        // Return the URL needed to open the corresponding Settings.
        var settingsURL = webViewURL

        // if autofill_default is checked and prefs.getString DNE, 'Automatically match with password'/"first" otherwise "never"
        val defValue = if (settings!!.getBoolean("autofill_default", true)) "/first" else "/never"
        val prefs: SharedPreferences = getSharedPreferences("autofill_web", Context.MODE_PRIVATE)
        var preference: String

        preference = defValue
        if (webViewURL != null) {
            val webViewUrlLowerCase = webViewURL.toLowerCase(Locale.ROOT)
            val prefsMap = prefs.all
            for (key in prefsMap.keys) {
                // for websites unlike apps there can be blank preference of "" which
                // means use default, so ignore it.
                val value = prefs.getString(key, null)
                val keyLowerCase = key.toLowerCase(Locale.ROOT)
                if (value != null && value != ""
                        && (webViewUrlLowerCase.contains(keyLowerCase) || keyLowerCase.contains(webViewUrlLowerCase))) {
                    preference = value
                    settingsURL = key
                }
            }
        }

        when (preference) {
            "/first" -> {
                if (!PasswordRepository.isInitialized()) {
                    PasswordRepository.initialize(this)
                }
                items = searchPasswords(PasswordRepository.getRepositoryDirectory(this), webViewTitle)
            }
            "/never" -> items = ArrayList()
            else -> getPreferredPasswords(preference)
        }

        return settingsURL!!
    }

    private fun setAppMatchingPasswords(appName: String, packageName: String) {
        // if autofill_default is checked and prefs.getString DNE, 'Automatically match with password'/"first" otherwise "never"
        val defValue = if (settings!!.getBoolean("autofill_default", true)) "/first" else "/never"
        val prefs: SharedPreferences = getSharedPreferences("autofill", Context.MODE_PRIVATE)
        val preference: String?

        preference = prefs.getString(packageName, defValue) ?: defValue

        when (preference) {
            "/first" -> {
                if (!PasswordRepository.isInitialized()) {
                    PasswordRepository.initialize(this)
                }
                items = searchPasswords(PasswordRepository.getRepositoryDirectory(this), appName)
            }
            "/never" -> items = ArrayList()
            else -> getPreferredPasswords(preference)
        }
    }

    // Put the newline separated list of passwords from the SharedPreferences
    // file into the items list.
    private fun getPreferredPasswords(preference: String) {
        if (!PasswordRepository.isInitialized()) {
            PasswordRepository.initialize(this)
        }
        val preferredPasswords = preference.splitLines()
        items = ArrayList()
        for (password in preferredPasswords) {
            val path = PasswordRepository.getRepositoryDirectory(applicationContext).toString() + "/" + password + ".gpg"
            if (File(path).exists()) {
                items.add(File(path))
            }
        }
    }

    private fun searchPasswords(path: File?, appName: String): ArrayList<File> {
        val passList = PasswordRepository.getFilesList(path)

        if (passList.size == 0) return ArrayList()

        val items = ArrayList<File>()

        for (file in passList) {
            if (file.isFile) {
                if (!file.isHidden && appName.toLowerCase(Locale.ROOT).contains(file.name.toLowerCase(Locale.ROOT).replace(".gpg", ""))) {
                    items.add(file)
                }
            } else {
                if (!file.isHidden) {
                    items.addAll(searchPasswords(file, appName))
                }
            }
        }
        return items
    }

    private fun showPasteUsernameDialog(node: AccessibilityNodeInfo, password: PasswordEntry) {
        if (dialog != null) {
            dialog!!.dismiss()
            dialog = null
        }

        val builder = AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog)
        builder.setNegativeButton(R.string.dialog_cancel) { _, _ ->
            dialog!!.dismiss()
            dialog = null
        }
        builder.setPositiveButton(R.string.autofill_paste) { _, _ ->
            pasteText(node, password.username)
            dialog!!.dismiss()
            dialog = null
        }
        builder.setMessage(getString(R.string.autofill_paste_username, password.username))

        dialog = builder.create()
        this.setDialogType(dialog)
        dialog!!.window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog!!.window!!.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog!!.show()
    }

    private fun showSelectPasswordDialog(packageName: String, appName: String, isWeb: Boolean) {
        if (dialog != null) {
            dialog!!.dismiss()
            dialog = null
        }

        val builder = AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog)
        builder.setNegativeButton(R.string.dialog_cancel) { _, _ ->
            dialog!!.dismiss()
            dialog = null
        }
        builder.setNeutralButton("Settings") { _, _ ->
            //TODO make icon? gear?
            // the user will have to return to the app themselves.
            val intent = Intent(this@AutofillService, AutofillPreferenceActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("packageName", packageName)
            intent.putExtra("appName", appName)
            intent.putExtra("isWeb", isWeb)
            startActivity(intent)
        }

        // populate the dialog items, always with pick + pick and match. Could
        // make it optional (or make height a setting for the same effect)
        val itemNames = arrayOfNulls<CharSequence>(items.size + 2)
        for (i in items.indices) {
            itemNames[i] = items[i].name.replace(".gpg", "")
        }
        itemNames[items.size] = getString(R.string.autofill_pick)
        itemNames[items.size + 1] = getString(R.string.autofill_pick_and_match)
        builder.setItems(itemNames) { _, which ->
            lastWhichItem = which
            when {
                which < items.size -> bindDecryptAndVerify()
                which == items.size -> {
                    val intent = Intent(this@AutofillService, AutofillActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    intent.putExtra("pick", true)
                    startActivity(intent)
                }
                else -> {
                    lastWhichItem--    // will add one element to items, so lastWhichItem=items.size()+1
                    val intent = Intent(this@AutofillService, AutofillActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    intent.putExtra("pickMatchWith", true)
                    intent.putExtra("packageName", packageName)
                    intent.putExtra("isWeb", isWeb)
                    startActivity(intent)
                }
            }
        }

        dialog = builder.create()
        setDialogType(dialog)
        dialog?.window?.apply {
            val height = 200
            val density = context.resources.displayMetrics.density
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // arbitrary non-annoying size
            setLayout((240 * density).toInt(), (height * density).toInt())
        }
        dialog?.show()
    }

    private fun setDialogType(dialog: AlertDialog?) {
        dialog?.window?.apply {
            setType(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    else
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            )
        }
    }

    override fun onInterrupt() {}

    private fun bindDecryptAndVerify() {
        if (serviceConnection!!.service == null) {
            // the service was disconnected, need to bind again
            // give it a listener and in the callback we will decryptAndVerify
            serviceConnection = OpenPgpServiceConnection(this@AutofillService, "org.sufficientlysecure.keychain", OnBoundListener())
            serviceConnection!!.bindToService()
        } else {
            decryptAndVerify()
        }
    }

    private fun decryptAndVerify() {
        packageName = info!!.packageName
        val data: Intent
        if (resultData == null) {
            data = Intent()
            data.action = OpenPgpApi.ACTION_DECRYPT_VERIFY
        } else {
            data = resultData!!
            resultData = null
        }
        var `is`: InputStream? = null
        try {
            `is` = FileUtils.openInputStream(items[lastWhichItem])
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val os = ByteArrayOutputStream()

        val api = OpenPgpApi(this@AutofillService, serviceConnection!!.service)
        // TODO we are dropping frames, (did we before??) find out why and maybe make this async
        val result = api.executeApi(data, `is`, os)
        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> {
                try {
                    val entry = PasswordEntry(os)
                    pasteText(info!!, entry.password)

                    // save password entry for pasting the username as well
                    if (entry.hasUsername()) {
                        lastPassword = entry
                        val ttl = Integer.parseInt(settings!!.getString("general_show_time", "45")!!)
                        Toast.makeText(this, getString(R.string.autofill_toast_username, ttl), Toast.LENGTH_LONG).show()
                        lastPasswordMaxDate = System.currentTimeMillis() + ttl * 1000L
                    }
                } catch (e: UnsupportedEncodingException) {
                    Log.e(Constants.TAG, "UnsupportedEncodingException", e)
                }

            }
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                Log.i("PgpHandler", "RESULT_CODE_USER_INTERACTION_REQUIRED")
                val pi = result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
                // need to start a blank activity to call startIntentSenderForResult
                val intent = Intent(this@AutofillService, AutofillActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra("pending_intent", pi)
                startActivity(intent)
            }
            OpenPgpApi.RESULT_CODE_ERROR -> {
                val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                Toast.makeText(this@AutofillService,
                        "Error from OpenKeyChain : " + error.message,
                        Toast.LENGTH_LONG).show()
                Log.e(Constants.TAG, "onError getErrorId:" + error.errorId)
                Log.e(Constants.TAG, "onError getMessage:" + error.message)
            }
        }
    }

    private fun pasteText(node: AccessibilityNodeInfo, text: String?) {
        // if the user focused on something else, take focus back
        // but this will open another dialog...hack to ignore this
        // & need to ensure performAction correct (i.e. what is info now?)
        ignoreActionFocus = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            var clip = ClipData.newPlainText("autofill_pm", text)
            clipboard.setPrimaryClip(clip)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            clip = ClipData.newPlainText("autofill_pm", "")
            clipboard.setPrimaryClip(clip)
            if (settings!!.getBoolean("clear_clipboard_20x", false)) {
                for (i in 0..19) {
                    clip = ClipData.newPlainText(i.toString(), i.toString())
                    clipboard.setPrimaryClip(clip)
                }
            }
        }
        node.recycle()
    }

    internal object Constants {
        const val TAG = "Keychain"
    }

    private inner class OnBoundListener : OpenPgpServiceConnection.OnBound {
        override fun onBound(service: IOpenPgpService2) {
            decryptAndVerify()
        }

        override fun onError(e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        var instance: AutofillService? = null
            private set
    }
}
