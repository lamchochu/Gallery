package org.fossify.gallery.activities

import android.os.Bundle
import android.view.WindowManager
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.cloud.CloudCrypto
import org.fossify.gallery.cloud.CloudSecrets
import org.fossify.gallery.cloud.CloudSyncManager
import org.fossify.gallery.databinding.ActivityCloudSyncBinding
import org.fossify.gallery.extensions.config

class CloudSyncActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityCloudSyncBinding::inflate)
    private val secrets by lazy { CloudSecrets(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.cloudSyncAppbar),
            padBottomSystem = listOf(binding.cloudSyncScroll)
        )
        setupMaterialScrollListener(binding.cloudSyncScroll, binding.cloudSyncAppbar)

        // never show the stored secrets again; the user only sees whether it is configured
        val configured = secrets.load() != null
        if (configured) {
            binding.cloudSyncStatus.text = getString(R.string.cloud_connected)
        }

        binding.cloudSyncGenerateKey.setOnClickListener {
            val key = CloudCrypto.generateRecoveryKey()
            binding.cloudSyncKey.setText(key)
            binding.cloudSyncStatus.text = getString(R.string.cloud_key_created, key)
        }
        binding.cloudSyncSave.setOnClickListener { saveAndTest() }
        binding.cloudSyncAddFolder.setOnClickListener { addFolder() }
        binding.cloudSyncClearFolders.setOnClickListener {
            config.cloudSyncFolders = HashSet()
            updateFolders()
        }
        binding.cloudSyncAuto.isChecked = config.cloudAutoSync
        binding.cloudSyncAuto.setOnCheckedChangeListener { _, checked -> config.cloudAutoSync = checked }
        binding.cloudSyncNow.setOnClickListener { syncNow() }
        updateFolders()
        updateTextColors(binding.cloudSyncHolder)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.cloudSyncAppbar, NavigationIcon.Arrow)
    }

    private fun updateFolders() {
        val folders = config.cloudSyncFolders
        binding.cloudSyncFolders.text =
            if (folders.isEmpty()) getString(R.string.cloud_no_folders) else folders.sorted().joinToString("\n")
    }

    private fun addFolder() {
        FilePickerDialog(this, internalStoragePath, false, config.shouldShowHidden, false, true) {
            val folders = config.cloudSyncFolders
            folders.add(it)
            config.cloudSyncFolders = folders
            updateFolders()
        }
    }

    private fun saveAndTest() {
        val token = binding.cloudSyncToken.text.toString().trim()
        val chat = binding.cloudSyncChat.text.toString().trim()
        val key = binding.cloudSyncKey.text.toString()
        if (token.isEmpty() || chat.isEmpty() || CloudCrypto.normalizePassphrase(key).isEmpty()) {
            toast(R.string.cloud_fill_all)
            return
        }
        binding.cloudSyncSave.isEnabled = false
        binding.cloudSyncStatus.text = "…"
        Thread {
            val master = CloudCrypto.deriveMasterKey(key, chat)
            val problem = CloudSyncManager(this).checkSetup(token, chat, master)
            runOnUiThread {
                binding.cloudSyncSave.isEnabled = true
                if (problem == null) {
                    secrets.save(token, chat, master)
                    config.cloudSyncEnabled = true
                    binding.cloudSyncToken.setText("")
                    binding.cloudSyncKey.setText("")
                    binding.cloudSyncStatus.text = getString(R.string.cloud_connected)
                } else {
                    binding.cloudSyncStatus.text = problem
                }
            }
        }.start()
    }

    private fun syncNow() {
        if (secrets.load() == null) {
            toast(R.string.cloud_setup_first)
            return
        }
        if (CloudSyncManager.isRunning()) return
        binding.cloudSyncNow.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val message = try {
                val r = CloudSyncManager(this).sync { status -> runOnUiThread { binding.cloudSyncStatus.text = status } }
                getString(R.string.cloud_sync_done, r.uploaded, r.downloaded, r.failed)
            } catch (e: Exception) {
                getString(R.string.cloud_sync_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.cloudSyncNow.isEnabled = true
                binding.cloudSyncStatus.text = message
            }
        }.start()
    }
}
