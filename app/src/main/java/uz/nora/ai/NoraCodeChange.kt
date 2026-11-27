
package uz.nora.ai

data class NoraCodeChange(
    val owner: String,
    val repo: String,
    val path: String,
    val oldContent: String,
    val newContent: String,
    val commitMessage: String
) {

    fun preview(): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        var prefix = 0
        while (
            prefix < oldLines.size &&
            prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix++
        }

        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] ==
            newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }

        val removed = oldLines.subList(
            prefix,
            oldLines.size - suffix
        )

        val added = newLines.subList(
            prefix,
            newLines.size - suffix
        )

        val output = StringBuilder()
        output.appendLine("REPOSITORY: $owner/$repo")
        output.appendLine("FAYL: $path")
        output.appendLine("ESKI QATORLAR: ${oldLines.size}")
        output.appendLine("YANGI QATORLAR: ${newLines.size}")
        output.appendLine("O'CHIRILADIGAN QATOR: ${removed.size}")
        output.appendLine("QO'SHILADIGAN QATOR: ${added.size}")
        output.appendLine()
        output.appendLine("O'ZGARISH KO'RINISHI:")

        if (removed.isEmpty() && added.isEmpty()) {
            output.appendLine("O'zgarish yo'q.")
        } else {
            val contextStart = (prefix - 3).coerceAtLeast(0)
            val contextEnd = (prefix + suffix + 3)
                .coerceAtMost(oldLines.size)

            for (i in contextStart until prefix) {
                output.appendLine("  ${oldLines[i]}")
            }

            var shown = 0
            for (line in removed) {
                if (shown >= 100) {
                    output.appendLine("... o'chirilgan qatorlar qisqartirildi.")
                    break
                }
                output.appendLine("- $line")
                shown++
            }

            shown = 0
            for (line in added) {
                if (shown >= 100) {
                    output.appendLine("... yangi qatorlar qisqartirildi.")
                    break
                }
                output.appendLine("+ $line")
                shown++
            }

            for (i in (oldLines.size - suffix) until contextEnd) {
                if (i >= 0 && i < oldLines.size) {
                    output.appendLine("  ${oldLines[i]}")
                }
            }
        }

        output.appendLine()
        output.appendLine("COMMIT: $commitMessage")
        output.appendLine()
        output.appendLine("Eslatma: bu qisqa ko'rinish; GitHub'dagi fayl faqat tasdiqdan keyin yangilanadi.")

        return output.toString()
    }

    fun validate(): Boolean {
        if (owner.isBlank() || repo.isBlank()) return false
        if (!owner.matches(Regex("^[A-Za-z0-9_.-]{1,100}$"))) return false
        if (!repo.matches(Regex("^[A-Za-z0-9_.-]{1,100}$"))) return false

        if (path.isBlank() || path.startsWith("/")) return false
        if (path.contains('\\')) return false

        if (path.split("/").any {
                it.isBlank() || it == "." || it == ".."
            }
        ) return false

        if (newContent.isBlank()) return false
        if (newContent.length > 500_000) return false
        if (commitMessage.isBlank()) return false
        if (commitMessage.length > 200) return false

        return true
    }
}
