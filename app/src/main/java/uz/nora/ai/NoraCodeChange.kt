
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

        val output = StringBuilder()
        output.appendLine("REPOSITORY: $owner/$repo")
        output.appendLine("FAYL: $path")
        output.appendLine("ESKI QATORLAR: ${oldLines.size}")
        output.appendLine("YANGI QATORLAR: ${newLines.size}")
        output.appendLine()
        output.appendLine("O'ZGARISH KO'RINISHI:")

        val max = maxOf(oldLines.size, newLines.size)
        var shown = 0

        for (i in 0 until max) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)

            if (oldLine == newLine) continue

            if (oldLine != null) {
                output.appendLine("- $oldLine")
                shown++
            }

            if (newLine != null) {
                output.appendLine("+ $newLine")
                shown++
            }

            if (shown >= 100) {
                output.appendLine("... Ko'rinish 100 o'zgarish bilan cheklangan.")
                break
            }
        }

        if (shown == 0) {
            output.appendLine("O'zgarish yo'q.")
        }

        output.appendLine()
        output.appendLine("COMMIT: $commitMessage")

        return output.toString()
    }

    fun validate(): Boolean {
        if (owner.isBlank() || repo.isBlank()) return false
        if (path.isBlank() || path.startsWith("/")) return false
        if (path.contains('\\')) return false
        if (path.split("/").any {
                it.isBlank() || it == "." || it == ".."
            }
        ) return false

        if (newContent.isBlank()) return false
        if (newContent.length > 500_000) return false
        if (commitMessage.isBlank()) return false

        return true
    }
}
