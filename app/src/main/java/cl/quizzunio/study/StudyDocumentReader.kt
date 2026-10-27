package cl.quizzunio.study

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream

data class StudyDocument(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val type: String,
    val text: String
)

/** Lee una copia del texto; no sube el documento a ningún servidor. */
class StudyDocumentReader(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun read(uri: Uri): StudyDocument {
        return try {
            readDocument(uri)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("No se pudo acceder al archivo. Vuelve a seleccionarlo en el explorador.", error)
        } catch (error: XmlPullParserException) {
            throw IllegalArgumentException("El documento de Office está dañado o usa un formato XML no compatible.", error)
        } catch (error: IOException) {
            throw IllegalArgumentException("No se pudo leer el archivo. Comprueba que esté descargado, sea válido y no tenga contraseña.", error)
        }
    }

    private fun readDocument(uri: Uri): StudyDocument {
        require(uri.scheme == "content") { "Selecciona el archivo desde el explorador de documentos." }
        var name = ""
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val type = when (extension) {
            "txt", "pdf", "docx", "pptx" -> extension
            "doc", "ppt" -> throw IllegalArgumentException("Convierte los archivos .doc o .ppt a .docx, .pptx o PDF antes de importarlos.")
            "" -> when (resolver.getType(uri)) {
                "text/plain" -> "txt"
                "application/pdf" -> "pdf"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                else -> throw IllegalArgumentException("Formato no compatible. Usa TXT, PDF, DOCX o PPTX.")
            }
            else -> throw IllegalArgumentException("Formato no compatible. Usa TXT, PDF, DOCX o PPTX.")
        }
        require(declaredSize <= MAX_FILE_BYTES) { "El archivo supera el límite de 20 MB." }
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_FILE_BYTES) }
            ?: throw IllegalArgumentException("No se pudo abrir el archivo. Vuelve a seleccionarlo.")
        require(bytes.isNotEmpty()) { "El archivo está vacío." }
        val rawText = when (type) {
            "txt" -> readText(bytes)
            "pdf" -> readPdf(bytes)
            else -> readOffice(bytes, type)
        }
        val text = rawText.replace('\u00a0', ' ').replace("\r\n", "\n")
            .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        require(text.any { it.isLetter() }) {
            if (type == "pdf") "El PDF no contiene texto legible. Si es un escaneo, conviértelo con OCR antes de importarlo."
            else "El archivo no contiene texto de estudio legible."
        }
        require(text.length <= MAX_TEXT_CHARS) { "El documento contiene demasiado texto. Divide el material en archivos más pequeños." }
        return StudyDocument(uri.toString(), name.ifBlank { "Documento.$type" }, bytes.size.toLong(), type.uppercase(Locale.ROOT), text)
    }

    private fun readText(bytes: ByteArray): String {
        val charset: Charset
        val offset: Int
        when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> {
                charset = Charsets.UTF_8; offset = 3
            }
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> {
                charset = Charsets.UTF_16LE; offset = 2
            }
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> {
                charset = Charsets.UTF_16BE; offset = 2
            }
            else -> { charset = Charsets.UTF_8; offset = 0 }
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (error: CharacterCodingException) {
            require(offset == 0) { "La codificación del TXT no es válida. Guárdalo como UTF-8." }
            String(bytes, Charset.forName("windows-1252"))
        }
        require(text.none { it == '\u0000' } && text.count { it.isISOControl() && it !in "\n\r\t" } < 5) {
            "El TXT contiene datos binarios. Guarda el documento como texto UTF-8."
        }
        require(text.length <= MAX_TEXT_CHARS) { "El TXT contiene demasiado texto. Divide el material." }
        return text
    }

    private fun readPdf(bytes: ByteArray): String {
        require(bytes.take(1024).toByteArray().toString(Charsets.ISO_8859_1).contains("%PDF-")) {
            "El archivo no es un PDF válido."
        }
        PDFBoxResourceLoader.init(appContext)
        try {
            return PDDocument.load(bytes).use { document ->
                require(document.currentAccessPermission.canExtractContent()) { "Este PDF no permite extraer texto." }
                require(document.numberOfPages in 1..MAX_PDF_PAGES) { "Usa un PDF de entre 1 y 200 páginas." }
                val text = StringBuilder()
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = " "
                    paragraphEnd = "\n"
                    pageEnd = "\n"
                }
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    text.append(stripper.getText(document)).append('\n')
                    require(text.length <= MAX_TEXT_CHARS) { "El PDF contiene demasiado texto. Divide el material." }
                }
                text.toString()
            }
        } catch (error: InvalidPasswordException) {
            throw IllegalArgumentException("El PDF tiene contraseña. Importa una copia desbloqueada.", error)
        }
    }

    private fun readOffice(bytes: ByteArray, type: String): String {
        require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            "El archivo no es un $type válido o está protegido con contraseña."
        }
        val parts = mutableMapOf<String, ByteArray>()
        var expandedSize = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 4000) { "El documento tiene demasiados elementos. Divide el archivo." }
                val selected = if (type == "docx") entry.name == "word/document.xml"
                    else entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) ||
                        entry.name == "ppt/presentation.xml" || entry.name == "ppt/_rels/presentation.xml.rels"
                val output = if (selected) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expandedSize += count
                    require(expandedSize <= MAX_EXPANDED_BYTES) { "El documento descomprimido es demasiado grande." }
                    output?.write(buffer, 0, count)
                    require(output == null || output.size() <= MAX_XML_BYTES) { "Una sección del documento es demasiado grande." }
                }
                if (output != null) {
                    require(!parts.containsKey(entry.name)) { "El documento contiene secciones duplicadas o dañadas." }
                    parts[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val orderedParts = if (type == "docx") {
            listOf(parts["word/document.xml"] ?: throw IllegalArgumentException("El DOCX no contiene un documento válido."))
        } else {
            val relationships = mutableMapOf<String, String>()
            parseXml(parts["ppt/_rels/presentation.xml.rels"] ?: throw IllegalArgumentException("El PPTX está incompleto.")) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target").orEmpty()
                    val path = normalizeOfficeTarget(target)
                    if (id != null && path.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) &&
                        parser.getAttributeValue(null, "TargetMode") != "External") {
                        relationships[id] = path
                    }
                }
            }
            val slides = mutableListOf<ByteArray>()
            parseXml(parts["ppt/presentation.xml"] ?: throw IllegalArgumentException("El PPTX está incompleto.")) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sldId") {
                    val id = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "id" && parser.getAttributeNamespace(it).isNotEmpty() }
                        ?.let { parser.getAttributeValue(it) }
                    val path = relationships[id] ?: throw IllegalArgumentException("No se pudo leer el orden de las diapositivas.")
                    slides.add(parts[path] ?: throw IllegalArgumentException("Falta una diapositiva del PPTX."))
                }
            }
            require(slides.isNotEmpty()) { "El PPTX no contiene diapositivas legibles." }
            slides
        }
        val text = StringBuilder()
        orderedParts.forEach { part ->
            var inText = false
            parseXml(part) { parser ->
                val isDocumentNamespace = parser.namespace.orEmpty().let {
                    it.endsWith("/wordprocessingml/2006/main") || it.endsWith("/drawingml/2006/main") ||
                        it == "http://purl.oclc.org/ooxml/wordprocessingml/main" || it == "http://purl.oclc.org/ooxml/drawingml/main"
                }
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (isDocumentNamespace) {
                        when (parser.name) {
                            "t" -> inText = true
                            "br", "cr", "tab" -> text.append(' ')
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (inText) text.append(parser.text.orEmpty())
                    XmlPullParser.END_TAG -> if (isDocumentNamespace) {
                        if (parser.name == "t") inText = false
                        if (parser.name in listOf("p", "tr")) text.append('\n')
                        if (parser.name == "tc") text.append(' ')
                    }
                }
                require(text.length <= MAX_TEXT_CHARS) { "El documento contiene demasiado texto. Divide el material." }
            }
            text.append('\n')
        }
        return text.toString()
    }

    private fun parseXml(bytes: ByteArray, consume: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(parser.eventType != XmlPullParser.DOCDECL) { "El documento contiene una declaración XML no admitida." }
            consume(parser)
            parser.nextToken()
        }
    }

    private fun normalizeOfficeTarget(target: String): String {
        val segments = mutableListOf<String>()
        val path = if (target.startsWith('/')) target else "ppt/$target"
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex) else return ""
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "El archivo supera el límite de 20 MB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        val MIME_TYPES = arrayOf(
            "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 50 * 1024 * 1024
        private const val MAX_XML_BYTES = 8 * 1024 * 1024
        private const val MAX_TEXT_CHARS = 500_000
        private const val MAX_PDF_PAGES = 200
    }
}
