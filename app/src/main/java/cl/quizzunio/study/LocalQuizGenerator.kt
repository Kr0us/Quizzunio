package cl.quizzunio.study

import cl.quizzunio.core.QuizQuestion
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Generación extractiva local: oculta conceptos en citas del material y utiliza
 * otros conceptos del mismo documento como alternativas. No utiliza un modelo de IA.
 */
class LocalQuizGenerator {
    fun generate(text: String, questionCount: Int): List<QuizQuestion> {
        require(questionCount in 1..MAX_QUESTIONS) {
            "La cantidad de preguntas debe estar entre 1 y $MAX_QUESTIONS."
        }
        require(text.length <= MAX_TEXT_LENGTH) {
            "El material es demasiado extenso. Divídelo en archivos más pequeños."
        }

        val sentences = SENTENCE_SEPARATOR.splitToSequence(text.replace('\r', '\n'))
            .map { WHITESPACE.replace(it, " ").trim().trimStart('•', '–', '—').trim() }
            .filter { it.length in MIN_SENTENCE_LENGTH..MAX_SENTENCE_LENGTH }
            .filter { WORD.findAll(it).count() in 7..75 }
            .distinctBy(::normalize)
            .take(MAX_SENTENCES)
            .toList()

        val frequencies = mutableMapOf<String, Int>()
        for (sentence in sentences) {
            WORD.findAll(sentence).map { normalize(it.value) }.toSet().forEach { word ->
                frequencies[word] = (frequencies[word] ?: 0) + 1
            }
        }

        val candidates = sentences.flatMap { sentence ->
            val definition = definitionCandidate(sentence)
            val words = WORD.findAll(sentence)
                .filter { isUsefulWord(it.value) }
                .distinctBy { normalize(it.value) }
                .sortedByDescending {
                    val frequency = frequencies[normalize(it.value)] ?: 1
                    it.value.length.coerceAtMost(14) + frequency.coerceAtMost(8)
                }
                .take(3)
                .map { Candidate(sentence, it.value, 1) }
                .toList()
            listOfNotNull(definition) + words
        }.sortedByDescending { it.priority }

        // Si dos citas iguales con hueco admiten respuestas diferentes, se descartan.
        val ambiguousPrompts = candidates.groupBy { it.promptKey }
            .filterValues { group -> group.map { it.answerKey }.distinct().size > 1 }
            .keys
        val terms = candidates.distinctBy { it.answerKey }
            .take(MAX_TERMS)
            .map { Term(it.answer, it.answerKey, it.answerKey.count { c -> c == ' ' } + 1) }
        val random = Random(text.hashCode() xor questionCount)
        val usedSentences = mutableSetOf<String>()
        val usedAnswers = mutableSetOf<String>()
        val usedPrompts = mutableSetOf<String>()
        val questions = mutableListOf<QuizQuestion>()

        for (candidate in candidates) {
            if (candidate.promptKey in ambiguousPrompts ||
                candidate.sentenceKey in usedSentences ||
                candidate.answerKey in usedAnswers ||
                candidate.promptKey in usedPrompts
            ) continue

            val answerWords = candidate.answerKey.count { it == ' ' } + 1
            val alternatives = terms.asSequence()
                .filter { it.key != candidate.answerKey }
                .filter { abs(it.wordCount - answerWords) <= 1 }
                .filter { !containsTerm(candidate.sentenceKey, it.key) }
                .take(MAX_DISTRACTOR_POOL)
                .sortedBy { abs(it.label.length - candidate.answer.length) }
                .take(12)
                .toList()
                .shuffled(random)
                .take(3)
                .map { it.label }
            if (alternatives.size != 3) continue

            val answers = (alternatives + candidate.answer).shuffled(random)
            questions += QuizQuestion(
                question = "Según el material, completa la cita con el concepto que falta:\n«${candidate.prompt}»",
                answers = answers,
                correctAnswer = answers.indexOf(candidate.answer)
            )
            usedSentences += candidate.sentenceKey
            usedAnswers += candidate.answerKey
            usedPrompts += candidate.promptKey
            if (questions.size == questionCount) return questions
        }

        require(questions.size >= questionCount) {
            if (questions.isEmpty()) {
                "No se encontraron suficientes conceptos para crear preguntas con cuatro alternativas. " +
                    "Agrega material con varias frases explicativas y conceptos diferentes."
            } else {
                "Este material permite crear ${questions.size} preguntas distintas con cuatro alternativas. " +
                    "Reduce la cantidad solicitada o agrega más material."
            }
        }
        return questions
    }

    private fun definitionCandidate(sentence: String): Candidate? {
        val match = DEFINITION.find(sentence) ?: return null
        val subject = match.groups[1] ?: return null
        val answer = subject.value.trim()
        val words = WORD.findAll(answer).map { normalize(it.value) }.toList()
        if (words.size !in 1..6 || answer.length !in 3..70) return null
        if (words.all { it in STOP_WORDS } || words.any { it in INVALID_SUBJECT_WORDS }) return null
        if (normalize(answer) in GENERIC_SUBJECTS) return null
        return Candidate(sentence, answer, 2)
    }

    private fun isUsefulWord(value: String): Boolean {
        val key = normalize(value)
        return value.length in 6..30 && key !in STOP_WORDS && key !in GENERIC_SUBJECTS
    }

    private fun containsTerm(sentence: String, term: String): Boolean =
        " $sentence ".contains(" $term ")

    private fun normalize(value: String): String = NON_WORD.replace(
        MARKS.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT),
        " "
    ).trim()

    private data class Term(val label: String, val key: String, val wordCount: Int)

    private inner class Candidate(
        val sentence: String,
        val answer: String,
        val priority: Int
    ) {
        val answerKey = normalize(answer)
        val sentenceKey = normalize(sentence)
        // Se ocultan también las repeticiones para no revelar la respuesta en la cita.
        val prompt = Regex(
            "(?<![\\p{L}\\p{M}])${Regex.escape(answer)}(?![\\p{L}\\p{M}])",
            RegexOption.IGNORE_CASE
        ).replace(sentence, "_____")
        val promptKey = normalize(prompt.replace("_____", " HUECO "))
    }

    private companion object {
        const val MAX_QUESTIONS = 50
        const val MAX_TEXT_LENGTH = 2_000_000
        const val MAX_SENTENCES = 2_500
        const val MAX_TERMS = 1_500
        const val MAX_DISTRACTOR_POOL = 80
        const val MIN_SENTENCE_LENGTH = 45
        const val MAX_SENTENCE_LENGTH = 500
        val WORD = Regex("[\\p{L}][\\p{L}\\p{M}]*(?:[-’'][\\p{L}\\p{M}]+)*")
        val SENTENCE_SEPARATOR = Regex("(?<=[.!?])\\s+|\\n+")
        val WHITESPACE = Regex("\\s+")
        val MARKS = Regex("\\p{M}+")
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        val DEFINITION = Regex(
            "^(?:(?:el|la|los|las|un|una|unos|unas)\\s+)?" +
                "([\\p{L}][\\p{L}\\p{M}\\s’'()-]{1,70}?)" +
                "(?:\\s+(?:es|son|consiste\\s+en|consisten\\s+en|" +
                "se\\s+define\\s+como|se\\s+definen\\s+como|" +
                "se\\s+denomina|se\\s+conoce\\s+como)\\s+|:\\s+)",
            RegexOption.IGNORE_CASE
        )
        val GENERIC_SUBJECTS = setOf(
            "concepto", "conceptos", "definicion", "definiciones", "ejemplo", "ejemplos",
            "introduccion", "conclusion", "conclusiones", "resumen", "contenido", "contenidos",
            "caracteristicas", "objetivo", "objetivos", "tema", "temas", "unidad", "unidades",
            "capitulo", "capitulos", "documento", "documentos", "material", "materiales",
            "actividad", "actividades", "pregunta", "preguntas", "respuesta", "respuestas",
            "esto", "esta", "este", "estos", "estas", "ello", "ellos", "ellas", "aqui"
        )
        val INVALID_SUBJECT_WORDS = setOf(
            "que", "cuando", "donde", "porque", "aunque", "mientras", "ademas", "tambien",
            "como", "cada", "esto", "esta", "este", "estos", "estas", "ellos", "ellas"
        )
        val STOP_WORDS = setOf(
            "a", "al", "algo", "algun", "alguna", "algunas", "alguno", "algunos", "alla",
            "ante", "antes", "aquel", "aquella", "aquellas", "aquello", "aquellos", "aqui",
            "asi", "aun", "aunque", "bajo", "bien", "cada", "casi", "cierta", "ciertas",
            "cierto", "ciertos", "como", "con", "contra", "cual", "cuales", "cualquier",
            "cualquiera", "cuando", "cuanta", "cuantas", "cuanto", "cuantos", "de", "del",
            "demasiado", "demasiados", "dentro", "desde", "despues", "dicha", "dichas",
            "dicho", "dichos", "diferente", "diferentes", "donde", "durante", "e", "el",
            "ella", "ellas", "ello", "ellos", "en", "entre", "era", "eran", "es", "esa",
            "esas", "ese", "esos", "esta", "estaba", "estaban", "estado", "estamos", "estan",
            "estar", "estas", "este", "esto", "estos", "etcetera", "existe", "existen", "fue",
            "fueron", "ha", "haber", "hace", "hacen", "hacer", "hacia", "han", "hasta", "hay",
            "incluso", "la", "las", "le", "les", "lo", "los", "mas", "mayor", "mayores",
            "mediante", "mejor", "mejores", "menor", "menores", "menos", "mi", "mientras",
            "misma", "mismas", "mismo", "mismos", "muchas", "mucho", "muchos", "muy", "nada",
            "ni", "ningun", "ninguna", "ninguno", "no", "nos", "nuestra", "nuestras", "nuestro",
            "nuestros", "o", "otra", "otras", "otro", "otros", "para", "pero", "poco", "pocos",
            "por", "porque", "posible", "posibles", "primera", "primeras", "primero", "primeros",
            "puede", "pueden", "pues", "que", "quien", "quienes", "respecto", "saber", "se",
            "sea", "sean", "segun", "ser", "sera", "seran", "si", "siempre", "siendo", "sin",
            "sino", "sobre", "solamente", "solo", "son", "su", "sus", "tal", "tales", "tambien",
            "tampoco", "tan", "tanto", "tener", "tiene", "tienen", "toda", "todas", "todo",
            "todos", "tras", "trata", "un", "una", "unas", "uno", "unos", "usted", "ustedes",
            "varias", "varios", "veces", "vez", "y", "ya", "ademas", "actualmente",
            "anterior", "anteriores", "anteriormente", "posterior", "posteriores", "posteriormente",
            "general", "generales", "generalmente", "principal", "principales", "principalmente",
            "importante", "importantes", "necesario", "necesaria", "necesarios", "necesarias",
            "siguiente", "siguientes", "suficiente", "suficientes", "permite", "permiten", "permitir",
            "presenta", "presentan", "realizar", "realiza", "realizan", "utiliza", "utilizan", "utilizar",
            "debido", "deben", "deber", "deberia", "forma", "formas", "manera", "maneras", "parte",
            "partes", "punto", "puntos", "caso", "casos", "tipo", "tipos", "ejemplo", "ejemplos",
            "the", "and", "for", "with", "this", "that", "these", "those", "which", "where",
            "when", "there", "their", "about", "because", "between", "through", "without"
        )
    }
}
