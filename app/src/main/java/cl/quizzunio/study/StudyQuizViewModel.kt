package cl.quizzunio.study

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudyQuizState(
    val quizName: String = "",
    val topic: String = "",
    val questionCount: Int = 10,
    val documents: List<StudyDocument> = emptyList(),
    val quizzes: List<StudyQuiz> = emptyList(),
    val activeQuiz: StudyQuiz? = null,
    val answers: List<Int?> = emptyList(),
    val currentQuestionIndex: Int = 0,
    val isBusy: Boolean = true,
    val message: String? = null
) {
    val correctAnswers: Int
        get() {
            val quiz = activeQuiz ?: return 0
            return quiz.questions.indices.count { index ->
                answers.getOrNull(index) == quiz.questions[index].correctAnswer
            }
        }
}

/** Coordina importación, generación local y una sesión de respuestas independiente de la UI. */
class StudyQuizViewModel(
    private val repository: StudyQuizRepository,
    private val documentReader: StudyDocumentReader,
    private val generator: LocalQuizGenerator
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(StudyQuizState())
    val state: StateFlow<StudyQuizState> = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.load() }
                mutableState.update {
                    it.copy(
                        quizzes = result.quizzes,
                        isBusy = false,
                        message = if (result.unreadableCount > 0) {
                            "No se pudieron abrir ${result.unreadableCount} cuestionarios guardados. Sus archivos se conservaron."
                        } else null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(isBusy = false, message = "No se pudieron cargar los cuestionarios guardados.")
                }
            }
        }
    }

    fun updateQuizName(value: String) {
        if (!mutableState.value.isBusy) mutableState.update { it.copy(quizName = value.take(120)) }
    }

    fun updateTopic(value: String) {
        if (!mutableState.value.isBusy) mutableState.update { it.copy(topic = value.take(200)) }
    }

    fun changeQuestionCount(delta: Int) {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(questionCount = (it.questionCount + delta).coerceIn(1, 50))
        }
    }

    fun importDocuments(uris: List<Uri>) {
        val initial = mutableState.value
        if (initial.isBusy || uris.isEmpty()) return
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val documents = initial.documents.toMutableList()
            val messages = mutableListOf<String>()
            var characterCount = documents.sumOf { it.text.length }
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris.distinct()) {
                        ensureActive()
                        if (documents.any { it.id == uri.toString() }) continue
                        if (documents.size >= MAX_DOCUMENTS) {
                            messages += "Puedes adjuntar hasta $MAX_DOCUMENTS archivos."
                            break
                        }
                        try {
                            val document = documentReader.read(uri)
                            if (characterCount + document.text.length > MAX_CHARACTERS) {
                                messages += "${document.name}: el material combinado supera los $MAX_CHARACTERS caracteres."
                                continue
                            }
                            documents += document
                            characterCount += document.text.length
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            messages += exception.message ?: "No se pudo abrir uno de los archivos seleccionados."
                        }
                    }
                }
                mutableState.update {
                    it.copy(documents = documents.toList(), isBusy = false, message = messages.distinct().joinToString("\n").takeIf { text -> text.isNotBlank() })
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(documents = documents.toList(), isBusy = false, message = "No se pudo completar la importación de archivos.")
                }
            }
        }
    }

    fun removeDocument(id: String) {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(documents = it.documents.filterNot { document -> document.id == id })
        }
    }

    fun generateQuiz(onComplete: () -> Unit) {
        val initial = mutableState.value
        if (initial.isBusy) return
        if (initial.quizName.isBlank()) {
            showMessage("Escribe el nombre del cuestionario.")
            return
        }
        if (initial.documents.isEmpty()) {
            showMessage("Agrega al menos un archivo con texto para crear el cuestionario.")
            return
        }
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val quiz = try {
                val generated = withContext(Dispatchers.Default) {
                    generator.generate(initial.documents.joinToString("\n\n") { it.text }, initial.questionCount)
                }
                val created = StudyQuiz(
                    id = UUID.randomUUID().toString(),
                    title = initial.quizName.trim(),
                    topic = initial.topic.trim(),
                    questions = generated
                )
                withContext(Dispatchers.IO) { repository.save(created) }
                created
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(isBusy = false, message = exception.message ?: "No se pudo generar y guardar el cuestionario.")
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    quizzes = listOf(quiz) + it.quizzes,
                    activeQuiz = quiz,
                    answers = List(quiz.questions.size) { null },
                    currentQuestionIndex = 0,
                    quizName = "",
                    topic = "",
                    documents = emptyList(),
                    isBusy = false,
                    message = null
                )
            }
            onComplete()
        }
    }

    fun selectQuiz(id: String): Boolean {
        val current = mutableState.value
        if (current.isBusy) return false
        val quiz = current.quizzes.firstOrNull { it.id == id } ?: return false
        mutableState.update {
            it.copy(activeQuiz = quiz, answers = List(quiz.questions.size) { null }, currentQuestionIndex = 0, message = null)
        }
        return true
    }

    fun selectAnswer(index: Int) {
        val current = mutableState.value
        if (current.isBusy) return
        val question = current.activeQuiz?.questions?.getOrNull(current.currentQuestionIndex) ?: return
        if (index !in question.answers.indices || current.answers.getOrNull(current.currentQuestionIndex) != null) return
        mutableState.update {
            it.copy(answers = it.answers.toMutableList().apply { set(current.currentQuestionIndex, index) })
        }
    }

    fun previousQuestion() {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(currentQuestionIndex = (it.currentQuestionIndex - 1).coerceAtLeast(0))
        }
    }

    /** Devuelve true cuando la última pregunta está contestada y se puede finalizar. */
    fun nextQuestion(): Boolean {
        val current = mutableState.value
        if (current.isBusy || current.activeQuiz == null) return false
        if (current.answers.getOrNull(current.currentQuestionIndex) == null) {
            showMessage("Selecciona una respuesta antes de continuar.")
            return false
        }
        if (current.currentQuestionIndex == current.activeQuiz.questions.lastIndex) return true
        mutableState.update { it.copy(currentQuestionIndex = it.currentQuestionIndex + 1) }
        return false
    }

    fun finishQuiz(onComplete: () -> Unit = {}) {
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        if (current.isBusy) return
        if (current.answers.size != quiz.questions.size || current.answers.any { it == null }) {
            showMessage("Responde todas las preguntas antes de finalizar.")
            return
        }
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val completed = quiz.copy(completed = true)
            try {
                withContext(Dispatchers.IO) { repository.save(completed) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(isBusy = false, message = "No se pudo guardar la finalización. Inténtalo de nuevo.")
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    activeQuiz = completed,
                    quizzes = it.quizzes.map { saved -> if (saved.id == quiz.id) completed else saved },
                    isBusy = false
                )
            }
            onComplete()
        }
    }

    fun resetAttempt() {
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        if (!current.isBusy) mutableState.update {
            it.copy(answers = List(quiz.questions.size) { null }, currentQuestionIndex = 0, message = null)
        }
    }

    fun reviewAnswers() {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(currentQuestionIndex = 0, message = null)
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_DOCUMENTS = 8
        const val MAX_CHARACTERS = 500_000
    }
}

val LocalStudyQuizViewModel = staticCompositionLocalOf<StudyQuizViewModel?> { null }

@Composable
fun rememberStudyQuizViewModel(): StudyQuizViewModel {
    val context = LocalContext.current
    val activity = remember(context) { context.findStudyActivity() }
    return remember(activity) {
        val appContext = activity.applicationContext
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(StudyQuizViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return StudyQuizViewModel(
                    StudyQuizRepository(appContext),
                    StudyDocumentReader(appContext),
                    LocalQuizGenerator()
                ) as T
            }
        }
        ViewModelProvider(activity, factory)[StudyQuizViewModel::class.java]
    }
}

private fun Context.findStudyActivity(): ComponentActivity {
    var candidate: Context = this
    while (candidate is ContextWrapper) {
        if (candidate is ComponentActivity) return candidate
        val base = candidate.baseContext
        if (base === candidate) break
        candidate = base
    }
    error("La gestión de cuestionarios requiere una actividad de Android.")
}
