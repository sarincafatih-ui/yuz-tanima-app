package com.fatih.yuztanima

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var listPeople: LinearLayout
    private lateinit var txtCount: TextView
    private lateinit var txtEmpty: TextView
    private var pendingTarget: Class<*>? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingTarget
        pendingTarget = null
        if (granted && target != null) startActivity(Intent(this, target))
        else if (!granted) Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listPeople = findViewById(R.id.listPeople)
        txtCount = findViewById(R.id.txtCount)
        txtEmpty = findViewById(R.id.txtEmpty)

        findViewById<View>(R.id.btnOpen).setOnClickListener {
            if (FaceStore.all(this).isEmpty()) {
                Toast.makeText(this, "Önce en az bir kişi tanımlayın", Toast.LENGTH_LONG).show()
            } else openWithCamera(RecognizeActivity::class.java)
        }
        findViewById<View>(R.id.btnRegister).setOnClickListener {
            openWithCamera(RegisterActivity::class.java)
        }
    }

    override fun onResume() {
        super.onResume()
        renderPeople()
    }

    private fun openWithCamera(target: Class<*>) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, target))
        } else {
            pendingTarget = target
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun renderPeople() {
        val people = FaceStore.all(this)
        listPeople.removeAllViews()
        txtCount.text = "${people.size} kişi"
        txtEmpty.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        people.forEach { p ->
            val row = inflater.inflate(R.layout.item_person, listPeople, false)
            row.findViewById<TextView>(R.id.txtInitials).text = p.initials
            row.findViewById<TextView>(R.id.txtName).text = p.fullName
            row.findViewById<TextView>(R.id.txtMeta).text = "${p.embeddings.size} yüz örneği"
            row.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Kişiyi sil")
                    .setMessage("${p.fullName} silinsin mi?")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sil") { _, _ ->
                        FaceStore.delete(this, p.id)
                        renderPeople()
                    }
                    .show()
            }
            listPeople.addView(row)
        }
    }
}
