package com.meditag

import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escritor mínimo de hojas de cálculo .xlsx (Office Open XML), sin librerías externas.
 * El XML va siempre en UTF-8 declarado, así que tildes y eñes se ven bien en
 * Excel, Google Sheets, LibreOffice y los visores del móvil.
 */
object Xlsx {
    sealed interface Cell
    data class Text(val v: String) : Cell
    data class Date(val v: LocalDate) : Cell
    data class Time(val v: LocalTime) : Cell
    data object Empty : Cell

    // Estilos (índices de cellXfs): 0 normal, 1 negrita, 2 fecha, 3 hora
    private const val S_BOLD = 1
    private const val S_DATE = 2
    private const val S_TIME = 3

    /**
     * @param header títulos de columna (en negrita, fila fija)
     * @param widths ancho de cada columna en caracteres
     */
    fun write(out: OutputStream, sheetName: String, header: List<String>, widths: List<Int>, rows: List<List<Cell>>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheetName))
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(header, widths, rows))
        }
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ' -> sb.append(ch)
                else -> {} // caracteres de control no válidos en XML
            }
        }
        return sb.toString()
    }

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private fun cellXml(ref: String, c: Cell, bold: Boolean = false): String = when (c) {
        is Text -> "<c r=\"$ref\" t=\"inlineStr\"${if (bold) " s=\"$S_BOLD\"" else ""}><is><t xml:space=\"preserve\">${esc(c.v)}</t></is></c>"
        is Date -> "<c r=\"$ref\" s=\"$S_DATE\"><v>${ChronoUnit.DAYS.between(EPOCH, c.v)}</v></c>"
        is Time -> "<c r=\"$ref\" s=\"$S_TIME\"><v>${String.format(java.util.Locale.ROOT, "%.8f", c.v.toSecondOfDay() / 86400.0)}</v></c>"
        Empty -> ""
    }

    private fun sheet(header: List<String>, widths: List<Int>, rows: List<List<Cell>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        sb.append("<row r=\"1\">")
        header.forEachIndexed { i, h -> sb.append(cellXml("${col(i)}1", Text(h), bold = true)) }
        sb.append("</row>")
        rows.forEachIndexed { ri, row ->
            val r = ri + 2
            sb.append("<row r=\"$r\">")
            row.forEachIndexed { i, c -> sb.append(cellXml("${col(i)}$r", c)) }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun workbook(name: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${esc(name.take(31))}" sheetId="1" r:id="rId1"/></sheets>""" +
            "</workbook>"

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            "</Types>"

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            "</Relationships>"

    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            "</Relationships>"

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="2"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/><numFmt numFmtId="165" formatCode="hh:mm"/></numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="4">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            "</cellXfs>" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            "</styleSheet>"
}
