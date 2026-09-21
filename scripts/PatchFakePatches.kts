#!/usr/bin/env kotlin
import java.io.File
import kotlin.system.exitProcess

/** sed '/pattern/a text' —— 在匹配到的每一行之后插入若干新行 */
fun File.insertAfter(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    for (line in readLines()) {
        out.add(line)
        if (anchor.containsMatchIn(line)) out.addAll(newLines)
    }
    writeText(out.joinToString("\n") + "\n")
}

fun File.insertAfterFirst(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    var inserted = false
    for (line in readLines()) {
        out.add(line)
        if (!inserted && anchor.containsMatchIn(line)) {
            out.addAll(newLines)
            inserted = true
        }
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/i text' —— 在匹配到的每一行之前插入若干新行 */
fun File.insertBefore(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    for (line in readLines()) {
        if (anchor.containsMatchIn(line)) out.addAll(newLines)
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

fun File.insertBeforeFirst(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    var inserted = false
    for (line in readLines()) {
        if (!inserted && anchor.containsMatchIn(line)) {
            out.addAll(newLines)
            inserted = true
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/d' —— 删除匹配到的行 */
fun File.deleteLine(pattern: Regex) {
    val out = readLines().filterNot { pattern.containsMatchIn(it) }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/,+N d' —— 删除匹配行及其后 N 行 */
fun File.deleteLineAndFollowing(pattern: Regex, extraLines: Int) {
    val out = mutableListOf<String>()
    var skip = 0
    for (line in readLines()) {
        if (skip > 0) {
            skip--
            continue
        }
        if (pattern.containsMatchIn(line)) {
            skip = extraLines
            continue
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/start/,/end/d' —— 删除从 start 到 end(含首尾)的整段,支持多段 */
fun File.deleteBlock(start: Regex, end: Regex) {
    val out = mutableListOf<String>()
    var inBlock = false
    for (line in readLines()) {
        if (!inBlock && start.containsMatchIn(line)) {
            inBlock = true
            continue
        }
        if (inBlock) {
            if (end.containsMatchIn(line)) inBlock = false
            continue
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** perl -pe 's/pattern/repl/' —— 逐行做正则替换(支持 pattern 内的反向引用 \1) */
fun File.replaceEachLine(pattern: Regex, replacement: String) {
    val out = readLines().map { pattern.replace(it, replacement) }
    writeText(out.joinToString("\n") + "\n")
}

/** sed 's/exact_old/exact_new/' —— 整行精确替换 */
fun File.replaceWholeLine(oldLine: String, newLine: String) {
    val out = readLines().map { if (it == oldLine) newLine else it }
    writeText(out.joinToString("\n") + "\n")
}

val kmi = System.getenv("KMI") ?: ""
val sublevel = (System.getenv("SUBLEVEL") ?: "0").toIntOrNull() ?: 0
val mode = args.getOrNull(0) ?: "apply" // apply | postfix | compat | revert
val workDir = args.getOrNull(1) ?: "kernel_workspace/kernel_platform/common"

fun f(relPath: String) = File(workDir, relPath)
fun File.rel(): String = this.path.removePrefix("$workDir/").removePrefix("$workDir\\")
fun logApply(file: File, detail: String) = println("[apply]-${file.rel()}: $detail")
fun logPostfix(file: File, detail: String) = println("[postfix]-${file.rel()}: $detail")
fun logRevert(file: File, detail: String) = println("[revert]-${file.rel()}: $detail")

val fdinfoCommentStart = Regex("""^[ \t]*/\*$""")
val fdinfoCommentEnd = Regex("""^[ \t]*u32 mask = mark->mask & IN_ALL_EVENTS;$""")

val inotifyFdinfoFuncAnchor = Regex("""^static void inotify_fdinfo\(struct seq_file \*m, struct fsnotify_mark \*mark\)$""")

fun addInotifyMarkUserMaskFunction(file: File) {
    file.insertBeforeFirst(
        inotifyFdinfoFuncAnchor,
        "static inline u32 inotify_mark_user_mask(struct fsnotify_mark *mark)",
        "{",
        "\treturn mark->mask & IN_ALL_EVENTS;",
        "}",
        ""
    )
}

fun applyFdinfo(file: File) {
    file.deleteBlock(fdinfoCommentStart, fdinfoCommentEnd)
    logApply(file, "removed inline mask-computation comment block ending at 'u32 mask = mark->mask & IN_ALL_EVENTS;' inside inotify_fdinfo()")
    file.replaceEachLine(Regex("""\bmask,\s*mark->ignored_mask"""), "inotify_mark_user_mask(mark)")
    logApply(file, "replaced seq_printf argument 'mask, mark->ignored_mask' with inotify_mark_user_mask(mark) call")
    file.replaceEachLine(Regex("""ignored_mask:%x"""), "ignored_mask:0")
    logApply(file, "replaced format specifier 'ignored_mask:%x' with literal 'ignored_mask:0'")
    addInotifyMarkUserMaskFunction(file)
    logApply(file, "inserted new static inline inotify_mark_user_mask() helper function before inotify_fdinfo()")
}

// android15-6.6 SUBLEVEL<=30 特殊单独处理

fun applyAndroid15VmaBlock(taskMmu: File, namespace: File) {
    // 1) 在含 smap_gather_stats(vma, &mss, last_vma_end); 的行之后插入新语句
    taskMmu.insertAfter(
        Regex("""smap_gather_stats\(vma, &mss, last_vma_end\);"""),
        "last_vma_end = vma->vm_end;"
    )
    logApply(taskMmu, "inserted 'last_vma_end = vma->vm_end;' immediately after the smap_gather_stats(vma, &mss, last_vma_end); call")

    // 2) 从文件末尾往前找最后一处 "last_vma_end = vma->vm_end;"，
    //    给它加缩进并在其后补一个 "}"，再往前找最近的
    //    "if (vma->vm_end > last_vma_end)" 把行尾的 ")" 换成 ") {"
    val lines = taskMmu.readLines().toMutableList()
    val ifPattern = Regex("""if\s*\(vma->vm_end > last_vma_end\)""")
    val trailingParen = Regex("""\)\s*$""")

    for (i in lines.indices.reversed()) {
        if (lines[i].contains("last_vma_end = vma->vm_end;")) {
            lines[i] = "\t\t\t\t" + lines[i]
            lines.add(i + 1, "\t\t\t}")
            for (j in i downTo 0) {
                if (ifPattern.containsMatchIn(lines[j])) {
                    lines[j] = trailingParen.replace(lines[j], ") {")
                    break
                }
            }
            break
        }
    }
    taskMmu.writeText(lines.joinToString("\n") + "\n")
    logApply(taskMmu, "opened a new '{ ... }' block by appending '{' to the trailing 'if (vma->vm_end > last_vma_end)' and inserting a closing '}' after the last 'last_vma_end = vma->vm_end;' statement, re-indenting that statement one level deeper")

    // 3) namespace.c: 在 trace/hooks/blk.h 之后插入 trace/hooks/fs.h
    namespace.insertAfter(Regex("""#include <trace/hooks/blk\.h>"""), "#include <trace/hooks/fs.h>")
    logApply(namespace, "added #include <trace/hooks/fs.h> directly after #include <trace/hooks/blk.h>")

    // 4) task_mmu.c: 在 "int ret = 0, copied = 0;" 之后插入两行
    taskMmu.insertAfter(
        Regex("""int ret = 0, copied = 0;"""),
        "\tunsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;",
        "\tpagemap_entry_t *res = NULL;"
    )
    logApply(taskMmu, "declared 'unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;' and 'pagemap_entry_t *res = NULL;' right after 'int ret = 0, copied = 0;'")
}

fun revertAndroid15VmaBlock(taskMmu: File, namespace: File) {
    namespace.deleteLine(Regex("""#include <trace/hooks/fs\.h>"""))
    logRevert(namespace, "removed #include <trace/hooks/fs.h>")
    taskMmu.deleteLine(Regex("""unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;"""))
    logRevert(taskMmu, "removed 'unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;' declaration")
    taskMmu.deleteLine(Regex("""pagemap_entry_t \*res = NULL;"""))
    logRevert(taskMmu, "removed 'pagemap_entry_t *res = NULL;' declaration")
}

/** SUSFS compatibility fixups
 *
 * SUSFS 2.3.x may add VMA_PAD_START()/__fold_filemap_fixup_entry() calls
 * inside the spoofed OPEN_REDIRECT path of show_map_vma().  Older OnePlus
 * 6.1 trees do not necessarily provide the newer page-migration helpers.
 *
 * Do NOT add a global VMA_PAD_START macro or a no-op global fold stub: that can
 * hide a real API mismatch and can also change unrelated task_mmu code.
 * Instead, only rewrite the deep-indented SUSFS OPEN_REDIRECT calls that were
 * injected by the SUSFS patch itself.  Native show_map_vma() calls are normally
 * less deeply indented and are left untouched.
 */
fun applyPostPatchFixups() {
    val taskMmu = f("fs/proc/task_mmu.c")
    if (taskMmu.exists()) {
        val before = taskMmu.readText()

        // SUSFS OPEN_REDIRECT spoofed path: use the universally available
        // vma->vm_end rather than the newer VMA_PAD_START() helper.
        taskMmu.replaceEachLine(
            Regex("""^([\\t ]{3,})end = VMA_PAD_START\\(vma\\);[\\t ]*$"""),
            "\\1end = vma->vm_end;"
        )

        // The fold helper is only needed for the newer page-size migration
        // implementation.  In the SUSFS spoofed path the exact fold operation
        // is not required; remove only the deep-indented injected call.
        taskMmu.deleteLine(
            Regex("""^[\\t ]{3,}__fold_filemap_fixup_entry\\(.*\\);[\\t ]*$""")
        )

        val after = taskMmu.readText()
        if (before != after) {
            logPostfix(taskMmu, "neutralized SUSFS OPEN_REDIRECT VMA_PAD_START/__fold_filemap_fixup_entry calls only in the deep-indented spoofed path")
        } else {
            logPostfix(taskMmu, "no deep-indented SUSFS VMA_PAD_START/__fold_filemap_fixup_entry calls found; no change required")
        }
    }

    if (kmi == "android12-5.10" || kmi == "android13-5.10") {
        val namei = f("fs/namei.c")
        if (namei.exists() && namei.readText().contains("set_nameidata(nd, old_dfd, fake_filename, NULL)")) {
            namei.replaceEachLine(
                Regex("""set_nameidata\\(nd, old_dfd, fake_filename, NULL\\)"""),
                "set_nameidata(nd, old_dfd, fake_filename)"
            )
            logPostfix(namei, "rewrote 4-arg set_nameidata(nd, old_dfd, fake_filename, NULL) calls to the 3-arg form set_nameidata(nd, old_dfd, fake_filename) required on this 5.10 baseline")
        }
    }

    if (kmi == "android16-6.12") {
        val openC = f("fs/open.c")
        if (openC.exists() && openC.readText().contains("getname_flags(filename, lookup_flags, NULL)")) {
            openC.replaceEachLine(
                Regex("""getname_flags\\(filename, lookup_flags, NULL\\)"""),
                "getname_flags(filename, lookup_flags)"
            )
            logPostfix(openC, "rewrote 3-arg getname_flags(filename, lookup_flags, NULL) calls to the 2-arg form getname_flags(filename, lookup_flags) required on this 6.12 baseline")
        }
    }
}

// Main
fun apply() {
    if (kmi == "android12-5.10") {
        if (sublevel <= 43) {
            val file = f("fs/proc/base.c")
            file.replaceEachLine(
                Regex("""(int|size_t)\s+this_len\s*=\s*min_t\s*\(\s*\1\s*,"""),
                "size_t this_len = min_t(size_t,"
            )
            logApply(file, "normalized 'this_len' declaration and its min_t() call to use size_t (was declared/cast as int on this baseline)")
        }
        if (sublevel <= 117) {
            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
    }

    if (kmi == "android13-5.10") {
        if (sublevel <= 107) {
            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
    }

    if (kmi == "android13-5.15") {
        if (sublevel <= 41) {
            val namespace = f("fs/namespace.c")
            namespace.insertAfter(
                Regex("""^#include <linux/shmem_fs\.h>$"""),
                "#include <linux/mnt_idmapping.h>"
            )
            logApply(namespace, "added #include <linux/mnt_idmapping.h> directly after #include <linux/shmem_fs.h>")

            val openC = f("fs/open.c")
            openC.insertAfter(
                Regex("""^#include <linux/compat\.h>$"""),
                "#include <linux/mnt_idmapping.h>"
            )
            logApply(openC, "added #include <linux/mnt_idmapping.h> directly after #include <linux/compat.h>")

            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
        if (sublevel >= 123) {
            val memory = f("mm/memory.c")
            memory.deleteLine(Regex("""#include <linux/swap_slots\.h>"""))
            logApply(memory, "removed #include <linux/swap_slots.h> (now provided elsewhere on this baseline)")
        }
        if (sublevel >= 197) {
            val namespace = f("fs/namespace.c")
            namespace.deleteLine(Regex("""^#include <trace/hooks/blk\.h>$"""))
            logApply(namespace, "removed #include <trace/hooks/blk.h>")
        }
        if (sublevel >= 206) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.deleteLine(Regex("""^#include <trace/hooks/mm\.h>$"""))
            logApply(taskMmu, "removed #include <trace/hooks/mm.h>")
        }
    }

    if (kmi == "android14-6.1") {
        if (sublevel <= 25) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <trace/events/oom\.h>$"""),
                "#include <trace/hooks/sched.h>"
            )
            logApply(base, "added #include <trace/hooks/sched.h> directly after #include <trace/events/oom.h>")
        }
        if (sublevel <= 141) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <linux/cpufreq_times\.h>$"""),
                "#include <linux/dma-buf.h>"
            )
            logApply(base, "added #include <linux/dma-buf.h> directly after #include <linux/cpufreq_times.h>")
        }
        if (sublevel >= 157) {
            val namespace = f("fs/namespace.c")
            namespace.deleteLine(Regex("""^#include <trace/hooks/blk\.h>$"""))
            logApply(namespace, "removed #include <trace/hooks/blk.h>")

            val superC = f("fs/super.c")
            superC.deleteLineAndFollowing(Regex("""^#include <trace/hooks/fs\.h>$"""), 1)
            logApply(superC, "removed #include <trace/hooks/fs.h>")
        }
    }

    if (kmi == "android15-6.6") {
        if (sublevel <= 30) {
            applyAndroid15VmaBlock(f("fs/proc/task_mmu.c"), f("fs/namespace.c"))
        }
        if (sublevel <= 57) {
            val memory = f("mm/memory.c")
            memory.insertAfter(
                Regex("""^#include <linux/sched/sysctl\.h>$"""),
                "#include <linux/zswap.h>"
            )
            logApply(memory, "added #include <linux/zswap.h> directly after #include <linux/sched/sysctl.h>")
        }
        if (sublevel <= 92) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <linux/cpufreq_times\.h>$"""),
                "#include <linux/dma-buf.h>"
            )
            logApply(base, "added #include <linux/dma-buf.h> directly after #include <linux/cpufreq_times.h>")
        }
    }

    if (kmi == "android16-6.12") {
        if (sublevel >= 58) {
            val exec = f("fs/exec.c")
            exec.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logApply(exec, "removed #include <linux/dma-buf.h>")
        }
        if (sublevel >= 69) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.replaceEachLine(Regex("""vma_data_pages"""), "vma_pages")
            logApply(taskMmu, "renamed all occurrences of vma_data_pages to vma_pages (upstream helper rename on this baseline)")
        }
    }

}


/**
 * SukiSU/KernelSU bridge compatibility fixups.
 *
 * These are deliberately discovered from the actual checked-out tree instead
 * of relying on KSU_KERNEL/KSU_FOLDER environment variables.
 */
fun findKsuRoot(): File? {
    val direct = f("drivers/kernelsu")
    val candidates = mutableListOf<File>()

    if (direct.exists() || direct.isDirectory) {
        candidates += try { direct.canonicalFile } catch (_: Exception) { direct }
    }

    // setup.sh normally creates drivers/kernelsu -> ../../KernelSU/kernel.
    // If the link is unavailable, locate the real KernelSU tree by its
    // characteristic files instead of assuming a fixed checkout path.
    File(workDir).walkTopDown()
        .filter { it.isDirectory && File(it, "ksu.c").isFile && File(it, "Makefile").isFile }
        .forEach { candidates += it }

    return candidates
        .map { try { it.canonicalFile } catch (_: Exception) { it } }
        .distinctBy { it.path }
        .firstOrNull { File(it, "ksu.c").isFile }
}

fun applySukiSuCompatFixups() {
    /*
     * SUSFS/manual-hook compatibility for kernels whose fs/exec.c contains
     * the legacy ksu_handle_post_execveat_sucompat() call.
     *
     * IMPORTANT: some SukiSU revisions still contain feature/sucompat.c,
     * while others reorganize the KernelSU sources.  If sucompat.c exists,
     * put the compatibility function there because this is the component
     * that owns the su-compat exec path.  Otherwise fall back to ksu.c,
     * which is part of the normal drivers/kernelsu build.
     */
    val execC = f("fs/exec.c")
    val postHookReferenced = execC.isFile &&
        execC.readText().contains("ksu_handle_post_execveat_sucompat")

    if (!postHookReferenced) {
        println("[compat] no legacy ksu_handle_post_execveat_sucompat reference in fs/exec.c")
        return
    }

    val ksuRoot = findKsuRoot()
    if (ksuRoot == null) {
        println("[compat] KernelSU source tree could not be resolved; cannot install post-exec compatibility symbol")
        return
    }

    val definitionRegex = Regex(
        """(?m)^\s*(?:static\s+)?(?:int|long|void)\s+ksu_handle_post_execveat_sucompat\s*\("""
    )

    val existingDefinition = ksuRoot.walkTopDown()
        .filter { it.isFile && it.extension == "c" }
        .any {
            try { definitionRegex.containsMatchIn(it.readText()) } catch (_: Exception) { false }
        }

    if (existingDefinition) {
        println("[compat] ksu_handle_post_execveat_sucompat() definition already exists in KernelSU tree")
        return
    }

    // Prefer the real sucompat.c when this SukiSU revision has one.
    val sucompatC = ksuRoot.walkTopDown()
        .filter { it.isFile && it.name == "sucompat.c" }
        .firstOrNull()

    val target = sucompatC ?: File(ksuRoot, "ksu.c")
    if (!target.isFile) {
        println("[compat] neither feature/sucompat.c nor ksu.c was found; cannot install compatibility symbol")
        return
    }

    target.appendText(
        """

        /*
         * SukiSU/SUSFS legacy post-exec compatibility shim.
         * Older SUSFS/manual-hook patches call this callback from fs/exec.c,
         * while newer SukiSU revisions may not provide the callback.
         * The modern exec handling happens before exec; this legacy callback
         * is intentionally a no-op and exists to satisfy the old ABI/link.
         */
        #ifdef CONFIG_KSU_SUSFS
        int ksu_handle_post_execveat_sucompat(
                int *fd,
                struct filename **filename_ptr,
                void *argv,
                void *envp,
                int *flags,
                int *retval)
        {
                (void)fd;
                (void)filename_ptr;
                (void)argv;
                (void)envp;
                (void)flags;
                (void)retval;
                return 0;
        }
        #endif
        """.trimIndent() + "\n"
    )
    logPostfix(target, "injected legacy ksu_handle_post_execveat_sucompat() into ${target.relativeTo(File(workDir))}")
}

fun postfix() {
    applyPostPatchFixups()
    applySukiSuCompatFixups()
}

fun revert() {
    if (kmi == "android12-5.10") {
        if (sublevel <= 43) {
            val file = f("fs/proc/base.c")
            file.replaceWholeLine(
                "size_t this_len = min_t(size_t, count, PAGE_SIZE);",
                "int this_len = min_t(int, count, PAGE_SIZE);"
            )
            logRevert(file, "restored 'int this_len = min_t(int, count, PAGE_SIZE);' declaration, undoing the size_t normalization")
        }
    }

    if (kmi == "android13-5.15") {
        if (sublevel <= 41) {
            val namespace = f("fs/namespace.c")
            namespace.deleteLine(Regex("""#include <linux/mnt_idmapping\.h>$"""))
            logRevert(namespace, "removed #include <linux/mnt_idmapping.h>")

            val openC = f("fs/open.c")
            openC.deleteLine(Regex("""#include <linux/mnt_idmapping\.h>$"""))
            logRevert(openC, "removed #include <linux/mnt_idmapping.h>")

            val susfs = f("fs/susfs.c")
            susfs.replaceEachLine(
                Regex(Regex.escape("i_uid_into_mnt(i_user_ns(&fi->inode), &fi->inode).val")),
                "i_uid_into_mnt(&init_user_ns, &fi->inode).val"
            )
            logRevert(susfs, "reverted i_uid_into_mnt(i_user_ns(&fi->inode), &fi->inode).val back to i_uid_into_mnt(&init_user_ns, &fi->inode).val")
            susfs.replaceEachLine(
                Regex(Regex.escape("i_uid_into_mnt(i_user_ns(inode), inode).val")),
                "i_uid_into_mnt(&init_user_ns, inode).val"
            )
            logRevert(susfs, "reverted i_uid_into_mnt(i_user_ns(inode), inode).val back to i_uid_into_mnt(&init_user_ns, inode).val")
        }
        if (sublevel >= 123) {
            val memory = f("mm/memory.c")
            memory.insertBefore(
                Regex("""#ifdef CONFIG_KSU_SUSFS_SUS_MAP"""),
                "#include <linux/swap_slots.h>"
            )
            logRevert(memory, "restored #include <linux/swap_slots.h> directly before #ifdef CONFIG_KSU_SUSFS_SUS_MAP")
        }
        if (sublevel >= 197) {
            val namespace = f("fs/namespace.c")
            namespace.insertAfter(
                Regex("""^#include "internal\.h"$"""),
                "#include <trace/hooks/blk.h>"
            )
            logRevert(namespace, "restored #include <trace/hooks/blk.h> directly after #include \"internal.h\"")
        }
        if (sublevel >= 206) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.insertAfter(
                Regex("""^#include <linux/pkeys\.h>$"""),
                "#include <trace/hooks/mm.h>"
            )
            logRevert(taskMmu, "restored #include <trace/hooks/mm.h> directly after #include <linux/pkeys.h>")
        }
    }

    if (kmi == "android14-6.1") {
        if (sublevel <= 25) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <trace/hooks/sched\.h>$"""))
            logRevert(base, "removed #include <trace/hooks/sched.h>")
        }
        if (sublevel <= 141) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logRevert(base, "removed #include <linux/dma-buf.h>")
        }
        if (sublevel >= 157) {
            val namespace = f("fs/namespace.c")
            namespace.insertAfter(
                Regex("""^#include "internal\.h"$"""),
                "#include <trace/hooks/blk.h>"
            )
            logRevert(namespace, "restored #include <trace/hooks/blk.h> directly after #include \"internal.h\"")

            val superC = f("fs/super.c")
            superC.insertAfter(
                Regex("""^#include "internal\.h"$"""),
                "#include <trace/hooks/fs.h>"
            )
            logRevert(superC, "restored #include <trace/hooks/fs.h> directly after #include \"internal.h\"")
        }
    }

    if (kmi == "android15-6.6") {
        if (sublevel <= 30) {
            revertAndroid15VmaBlock(f("fs/proc/task_mmu.c"), f("fs/namespace.c"))
        }
        if (sublevel <= 57) {
            val memory = f("mm/memory.c")
            memory.deleteLine(Regex("""^#include <linux/zswap\.h>$"""))
            logRevert(memory, "removed #include <linux/zswap.h>")
        }
        if (sublevel <= 92) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logRevert(base, "removed #include <linux/dma-buf.h>")
        }
    }

    if (kmi == "android16-6.12") {
        if (sublevel >= 58) {
            val exec = f("fs/exec.c")
            exec.insertAfterFirst(Regex("""^#include """), "#include <linux/dma-buf.h>")
            logRevert(exec, "restored #include <linux/dma-buf.h> directly after the first #include line")
        }
        if (sublevel >= 69) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.replaceEachLine(Regex("""vma_pages"""), "vma_data_pages")
            logRevert(taskMmu, "renamed all occurrences of vma_pages back to vma_data_pages")
        }
    }
}

when (mode) {
    "apply" -> apply()
    "postfix" -> postfix()
    "revert" -> revert()
    else -> {
        println("Usage: kotlin PatchFakePatches.main.kts <apply|postfix|revert> [workDir]")
        exitProcess(1)
    }
}