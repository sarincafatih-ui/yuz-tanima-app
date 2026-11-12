package com.fatih.yuztanima

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tanımlı bir kişi: ad, soyad ve birkaç yüz imzası (embedding). */
data class Person(
    val id: String,
    val name: String,
    val surname: String,
    val embeddings: List<FloatArray>,
    val createdAt: Long
) {
    val fullName: String get() = "$name $surname".trim()
    val initials: String
        get() = (name.take(1) + surname.take(1)).uppercase(java.util.Locale("tr"))
}

/**
 * Kişi kayıtlarının tutulduğu TEK yer.
 * Şu an telefonun içinde saklanıyor. İleride birden fazla telefon arasında
 * paylaşmak için (ör. Firebase) sadece bu sınıfı değiştirmek yeterli.
 */
object FaceStore {
    private const val PREFS = "yuz_tanima"
    private const val KEY = "people"

    fun all(context: Context): List<Person> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val embArr = o.getJSONArray("embeddings")
            val embeddings = (0 until embArr.length()).map { j ->
                val e = embArr.getJSONArray(j)
                FloatArray(e.length()) { k -> e.getDouble(k).toFloat() }
            }
            Person(
                id = o.getString("id"),
                name = o.getString("name"),
                surname = o.getString("surname"),
                embeddings = embeddings,
                createdAt = o.optLong("createdAt", 0L)
            )
        }
    }

    fun add(context: Context, name: String, surname: String, embeddings: List<FloatArray>): Person {
        val person = Person(UUID.randomUUID().toString(), name, surname, embeddings, System.currentTimeMillis())
        save(context, all(context) + person)
        return person
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    private fun save(context: Context, people: List<Person>) {
        val arr = JSONArray()
        people.forEach { p ->
            val embArr = JSONArray()
            p.embeddings.forEach { e ->
                val ja = JSONArray()
                e.forEach { ja.put(it.toDouble()) }
                embArr.put(ja)
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("surname", p.surname)
                    .put("createdAt", p.createdAt)
                    .put("embeddings", embArr)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
