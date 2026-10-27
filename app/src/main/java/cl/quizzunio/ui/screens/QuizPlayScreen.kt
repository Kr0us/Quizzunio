package cl.quizzunio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import cl.quizzunio.study.LocalStudyQuizViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.ui.theme.extendedColors

/**
 * Pantalla 08: Responder Cuestionario
 * Maqueta: docs/maquetas/claro/08_Responder_cuestionario.drawio y docs/maquetas/oscuro/08_Responder_cuestionario.drawio
 */
@Composable
fun QuizPlayScreen(
    onBackClick: () -> Unit = {},
    onFinishQuiz: () -> Unit = {}
) {
    val studyModel = LocalStudyQuizViewModel.current
    val studyState = studyModel?.state?.collectAsState()?.value
    val isBusy = studyState?.isBusy == true
    BackHandler(enabled = isBusy) { }
    if (studyModel != null && studyState?.activeQuiz == null) {
        LaunchedEffect(isBusy) { if (!isBusy) onBackClick() }
        return
    }

    // TODO: Implementar obtencion de preguntas en Core
    val previewQuestions = listOf<QuizQuestion>(
        QuizQuestion(
            "Hola, esto es una pregunta",
            listOf(
                "Respuesta 1",
                "Respuesta 2",
                "Respuesta 3",
                "Respuesta 4"
            ),
            2
        ),

        QuizQuestion(
            "Esta es otra pregunta",
            listOf(
                "Respuesta 1",
                "Respuesta 2",
                "Respuesta 3",
                "Respuesta 4"
            ),
            0
        ),
        QuizQuestion(
            "Esta es la última pregunta",
            listOf(
                "Respuesta 1",
                "Respuesta 2",
                "Respuesta 3",
                "Respuesta 4"
            ),
            1
        )
    )

    val questions = studyState?.activeQuiz?.questions ?: previewQuestions
    var previewSelectedOptionIndex by remember { mutableIntStateOf(-1) }
    var previewCurrentQuestionIndex by remember { mutableIntStateOf(1) }
    val currentQuestionIndex = studyState?.currentQuestionIndex?.plus(1) ?: previewCurrentQuestionIndex
    val selectedOptionIndex = if (studyState != null) studyState.answers.getOrNull(currentQuestionIndex - 1) ?: -1
        else previewSelectedOptionIndex
    val totalQuestions = questions.size
    val question = questions.getOrElse(currentQuestionIndex - 1) { questions.first() }
    val correctOptionIndex = question.correctAnswer

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick, enabled = !isBusy) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = "Quizzunio",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Progreso
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pregunta $currentQuestionIndex de $totalQuestions",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    val progressPercent = if (totalQuestions > 0) (currentQuestionIndex * 100) / totalQuestions else 0
                    Text(
                        text = "$progressPercent%",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Barra de progreso
                LinearProgressIndicator(
                    progress = { if (totalQuestions > 0) currentQuestionIndex.toFloat() / totalQuestions else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.extendedColors.success,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Tarjeta principal de la pregunta
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = question.question,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 26.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Opciones de respuesta con retroalimentación inmediata
                        question.answers.forEachIndexed { index, optionText ->
                            val isSelected = selectedOptionIndex == index

                            val cardBg: Color
                            val borderStroke: BorderStroke
                            val iconColor: Color
                            val iconText: String

                            when {
                                isSelected && index == correctOptionIndex -> {
                                    // Seleccionada y correcta (Verde)
                                    cardBg = MaterialTheme.extendedColors.successContainer
                                    borderStroke = BorderStroke(1.5.dp, MaterialTheme.extendedColors.success)
                                    iconColor = MaterialTheme.extendedColors.success
                                    iconText = "✓"
                                }
                                isSelected && index != correctOptionIndex -> {
                                    // Seleccionada e incorrecta (Rojo)
                                    cardBg = MaterialTheme.extendedColors.errorContainer
                                    borderStroke = BorderStroke(1.5.dp, MaterialTheme.extendedColors.error)
                                    iconColor = MaterialTheme.extendedColors.error
                                    iconText = "×"
                                }
                                else -> {
                                    // Estado normal sin seleccionar
                                    cardBg = MaterialTheme.colorScheme.secondaryContainer
                                    borderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    iconColor = MaterialTheme.colorScheme.secondary
                                    iconText = "○"
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable(enabled = selectedOptionIndex == -1 && !isBusy) {
                                        if (selectedOptionIndex == -1) {
                                            if (studyModel != null) studyModel.selectAnswer(index)
                                            else previewSelectedOptionIndex = index
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                border = borderStroke,
                                colors = CardDefaults.cardColors(
                                    containerColor = cardBg
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = iconText,
                                        fontSize = 18.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = iconColor,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )

                                    Text(
                                        text = optionText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }

                        // Retroalimentación en caso de respuesta incorrecta
                        if (selectedOptionIndex != -1 && selectedOptionIndex != correctOptionIndex) {
                            val maxAnswerLength = 35
                            val rawCorrectAnswer = question.answers.getOrNull(question.correctAnswer).orEmpty()
                            val formattedCorrectAnswer = if (rawCorrectAnswer.length > maxAnswerLength) {
                                rawCorrectAnswer.take(maxAnswerLength).trimEnd() + "..."
                            } else {
                                rawCorrectAnswer
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Respuesta Correcta: $formattedCorrectAnswer",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.extendedColors.error
                            )
                        }
                    }
                }
            }

            // Botones inferiores de navegación
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (studyModel != null) studyModel.previousQuestion()
                            else if (previewCurrentQuestionIndex > 1) {
                                previewCurrentQuestionIndex--
                                previewSelectedOptionIndex = -1
                            }
                        },
                        enabled = currentQuestionIndex > 1 && !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(
                            text = "Anterior",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            if (studyModel != null) {
                                if (studyModel.nextQuestion()) studyModel.finishQuiz(onFinishQuiz)
                            } else if (previewCurrentQuestionIndex < totalQuestions) {
                                previewCurrentQuestionIndex++
                                previewSelectedOptionIndex = -1
                            } else {
                                onFinishQuiz()
                            }
                        },
                        enabled = selectedOptionIndex != -1 && !isBusy,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = if (currentQuestionIndex == totalQuestions) "Finalizar" else "Siguiente",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Salir y guardar progreso",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { /* Guardar */ }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun QuizPlayScreenPreview() {
    QuizzunioTheme(
        darkTheme = true
    ) {
        QuizPlayScreen()
    }
}
