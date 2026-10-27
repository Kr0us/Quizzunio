package cl.quizzunio.study

import android.content.Context
import android.util.AtomicFile
import cl.quizzunio.core.QuizQuestion
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class StudyQuiz(
    val id: String,
    val title: String,
    val topic: String,
    val questions: List<QuizQuestion>,
    val completed: Boolean = false
)

data class StudyQuizLoadResult(
    val quizzes: List<StudyQuiz>,
    val unreadableCount: Int
)

/** Guarda los cuestionarios en el almacenamiento privado de la aplicación. */
class StudyQuizRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "study_quizzes")

    fun load(): StudyQuizLoadResult {
        if (!directory.exists()) return StudyQuizLoadResult(emptyList(), 0)
        val files = directory.listFiles() ?: throw IOException("No se pudieron leer los cuestionarios guardados.")
        val ids = files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            val name = file.name.removeSuffix(".bak")
            if (name.endsWith(".json")) name.removeSuffix(".json") else null
        }.filter { ID_PATTERN.matches(it) }.distinct()
        var unreadableCount = 0
        val quizzes = ids.mapNotNull { id ->
            try {
                val atomicFile = AtomicFile(File(directory, "$id.json"))
                val text = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (builder.length + count > MAX_SAVED_CHARACTERS) {
                            throw IOException("El cuestionario guardado supera el límite permitido.")
                        }
                        builder.append(buffer, 0, count)
                    }
                    builder.toString()
                }
                decode(JSONObject(text), id)
            } catch (exception: Exception) {
                // Se conserva el archivo para permitir su recuperación manual.
                unreadableCount++
                null
            }
        }
        return StudyQuizLoadResult(quizzes, unreadableCount)
    }

    fun save(quiz: StudyQuiz) {
        require(ID_PATTERN.matches(quiz.id)) { "El identificador del cuestionario no es válido." }
        validate(quiz)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("No se pudo preparar el almacenamiento del cuestionario.")
        }
        val encoded = encode(quiz).toString()
        require(encoded.length <= MAX_SAVED_CHARACTERS) { "El cuestionario supera el tamaño permitido." }
        val atomicFile = AtomicFile(File(directory, "${quiz.id}.json"))
        val stream = atomicFile.startWrite()
        try {
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (exception: Exception) {
            atomicFile.failWrite(stream)
            throw exception
        }
    }

    private fun encode(quiz: StudyQuiz): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", quiz.id)
        put("title", quiz.title)
        put("topic", quiz.topic)
        put("completed", quiz.completed)
        put("questions", JSONArray().apply {
            quiz.questions.forEach { question ->
                put(JSONObject().apply {
                    put("question", question.question)
                    put("answers", JSONArray(question.answers))
                    put("correctAnswer", question.correctAnswer)
                })
            }
        })
    }

    private fun decode(json: JSONObject, fileId: String): StudyQuiz {
        require(json.getInt("version") == 1)
        require(json.getString("id") == fileId)
        val entries = json.getJSONArray("questions")
        require(entries.length() in 1..50)
        val questions = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val answers = entry.getJSONArray("answers")
            require(answers.length() == 4)
            QuizQuestion(
                question = entry.getString("question"),
                answers = (0 until answers.length()).map { answers.getString(it) },
                correctAnswer = entry.getInt("correctAnswer")
            )
        }
        return StudyQuiz(
            id = fileId,
            title = json.getString("title"),
            topic = json.optString("topic"),
            questions = questions,
            completed = json.optBoolean("completed", false)
        ).also(::validate)
    }

    private fun validate(quiz: StudyQuiz) {
        require(quiz.title.isNotBlank() && quiz.title.length <= 120)
        require(quiz.topic.length <= 200)
        require(quiz.questions.size in 1..50)
        quiz.questions.forEach { question ->
            require(question.question.isNotBlank())
            require(question.answers.size == 4 && question.correctAnswer in question.answers.indices)
            require(question.answers.all { it.isNotBlank() })
            require(question.answers.map { it.trim().lowercase(java.util.Locale.ROOT) }.distinct().size == 4)
        }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-zA-Z0-9-]{1,80}")
        const val MAX_SAVED_CHARACTERS = 1_000_000
    }
}
