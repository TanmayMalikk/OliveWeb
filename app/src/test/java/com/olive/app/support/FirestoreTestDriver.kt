package com.olive.app.support

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

// Replaces the Firestore transport while repositories retain their real mapping and transaction logic.
internal class FirestoreTestDriver {
    val records = linkedMapOf<String, Map<String, Any?>>()
    val mutations = mutableListOf<Pair<String, Map<String, Any?>>>()
    val listeners = mutableListOf<Subscription>()
    private val documents = mutableMapOf<String, DocumentReference>()
    private val collections = mutableMapOf<String, CollectionReference>()
    private var generated = 0
    val db: FirebaseFirestore = mock(FirebaseFirestore::class.java, Answer { call ->
        when (call.method.name) {
            "collection" -> collection(call.getArgument(0))
            "batch" -> batch()
            "runTransaction" -> {
                val action = call.arguments.filterIsInstance<Transaction.Function<Any?>>().single()
                val changes = mutableListOf<() -> Unit>()
                val transaction = mock(Transaction::class.java, Answer { invocation ->
                    when (invocation.method.name) {
                        "get" -> snapshot((invocation.arguments[0] as DocumentReference).path)
                        "set", "update", "delete" -> {
                            changes += mutation(invocation)
                            null
                        }
                        else -> RETURNS_DEFAULTS.answer(invocation)
                    }
                })
                try {
                    val result = action.apply(transaction)
                    changes.forEach { it() }
                    Tasks.forResult(result)
                } catch (failure: Exception) { Tasks.forException<Any?>(failure) }
            }
            else -> RETURNS_DEFAULTS.answer(call)
        }
    })

    data class Subscription(
        val path: String,
        val document: Boolean,
        val query: QuerySpec,
        val receive: EventListener<Any>,
        var closed: Boolean = false,
    )

    data class QuerySpec(
        val equals: Map<String, Any?> = emptyMap(),
        val contains: Pair<String, Any?>? = null,
        val order: String? = null,
        val limit: Long? = null,
    )

    fun document(path: String): DocumentReference = documents.getOrPut(path) {
        mock(DocumentReference::class.java, Answer { call ->
            when (call.method.name) {
                "getPath" -> path
                "getId" -> path.substringAfterLast("/")
                "get" -> Tasks.forResult(snapshot(path))
                "collection" -> collection(path + "/" + call.getArgument<String>(0))
                "set", "update", "delete" -> { mutation(call, path)(); Tasks.forResult<Void>(null) }
                "addSnapshotListener" -> subscribe(path, true, QuerySpec(), call)
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
    }

    fun collection(path: String): CollectionReference = collections.getOrPut(path) {
        mock(CollectionReference::class.java, queryAnswer(path, QuerySpec()))
    }

    private fun query(path: String, spec: QuerySpec) = mock(Query::class.java, queryAnswer(path, spec))

    private fun queryAnswer(path: String, spec: QuerySpec): Answer<Any?> = Answer { call ->
        when (call.method.name) {
            "getPath" -> path
            "getId" -> path.substringAfterLast("/")
            "document" -> document(path + "/" + (call.arguments.firstOrNull() as? String ?: "generated-${++generated}"))
            "get" -> Tasks.forResult(querySnapshot(path, spec))
            "whereEqualTo" -> query(path, spec.copy(equals = spec.equals + (call.getArgument<String>(0) to call.arguments[1])))
            "whereArrayContains" -> query(path, spec.copy(contains = call.getArgument<String>(0) to call.arguments[1]))
            "orderBy" -> query(path, spec.copy(order = call.getArgument(0)))
            "limit", "limitToLast" -> query(path, spec.copy(limit = (call.arguments[0] as Number).toLong()))
            "addSnapshotListener" -> subscribe(path, false, spec, call)
            else -> RETURNS_DEFAULTS.answer(call)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribe(path: String, document: Boolean, query: QuerySpec, call: InvocationOnMock): ListenerRegistration {
        val receive = call.arguments.filterIsInstance<EventListener<Any>>().single()
        val listener = Subscription(path, document, query, receive)
        listeners += listener
        return mock(ListenerRegistration::class.java, Answer { invocation ->
            if (invocation.method.name == "remove") listener.closed = true
            RETURNS_DEFAULTS.answer(invocation)
        })
    }

    fun emit(path: String, cached: Boolean = false) {
        listeners.filter { it.path == path && !it.closed }.forEach {
            it.receive.onEvent(if (it.document) snapshot(path, cached) else querySnapshot(path, it.query, cached), null)
        }
    }

    fun fail(path: String, failure: FirebaseFirestoreException) {
        listeners.filter { it.path == path && !it.closed }.forEach { it.receive.onEvent(null, failure) }
    }

    fun snapshot(path: String, cached: Boolean = false): DocumentSnapshot =
        mock(DocumentSnapshot::class.java, Answer { call ->
            val data = records[path]
            when (call.method.name) {
                "getId" -> path.substringAfterLast("/")
                "getReference" -> document(path)
                "exists" -> data != null
                "getData" -> data
                "getMetadata" -> metadata(cached)
                "contains" -> data?.containsKey(call.getArgument<String>(0)) == true
                "get", "getString", "getTimestamp", "getBoolean" -> data?.get(call.getArgument<String>(0))
                "getLong" -> (data?.get(call.getArgument<String>(0)) as? Number)?.toLong()
                "getDouble" -> (data?.get(call.getArgument<String>(0)) as? Number)?.toDouble()
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })

    private fun querySnapshot(path: String, spec: QuerySpec, cached: Boolean = false): QuerySnapshot {
        val rows = records.entries.filter { (key, data) ->
            key.substringBeforeLast("/") == path &&
                spec.equals.all { (field, value) -> data[field] == value } &&
                (spec.contains?.let { (field, value) -> value in (data[field] as? List<*>).orEmpty() } ?: true)
        }.let { rows ->
            if (spec.order == null) rows else rows.sortedBy {
                (it.value[spec.order] as? Timestamp)?.toDate()?.time
                    ?: (it.value[spec.order] as? Number)?.toLong() ?: 0
            }
        }.let { rows -> spec.limit?.let { rows.takeLast(it.toInt()) } ?: rows }
        return mock(QuerySnapshot::class.java, Answer { call ->
            when (call.method.name) {
                "getDocuments" -> rows.map { snapshot(it.key, cached) }
                "isEmpty" -> rows.isEmpty()
                "size" -> rows.size
                "getMetadata" -> metadata(cached)
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
    }

    private fun metadata(cached: Boolean) = mock(SnapshotMetadata::class.java, Answer { call ->
        when (call.method.name) {
            "isFromCache" -> cached
            "hasPendingWrites" -> false
            else -> RETURNS_DEFAULTS.answer(call)
        }
    })

    private fun batch(): WriteBatch {
        val changes = mutableListOf<() -> Unit>()
        lateinit var batch: WriteBatch
        batch = mock(WriteBatch::class.java, Answer { call ->
            when (call.method.name) {
                "set", "update", "delete" -> { changes += mutation(call); batch }
                "commit" -> { changes.forEach { it() }; Tasks.forResult<Void>(null) }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
        return batch
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutation(call: InvocationOnMock, explicitPath: String? = null): () -> Unit {
        val path = explicitPath ?: (call.arguments[0] as DocumentReference).path
        val offset = if (explicitPath == null) 1 else 0
        val data = when (val input = call.arguments.getOrNull(offset)) {
            is Map<*, *> -> input as Map<String, Any?>
            is String -> mapOf(input to call.arguments.getOrNull(offset + 1))
            else -> emptyMap()
        }
        return {
            synchronized(records) {
                if (call.method.name == "delete") records.remove(path)
                else {
                    val merged = if (call.method.name == "set" && call.arguments.size == offset + 1) data
                        else records[path].orEmpty() + data
                    records[path] = merged
                    mutations += path to data
                }
            }
        }
    }
}
