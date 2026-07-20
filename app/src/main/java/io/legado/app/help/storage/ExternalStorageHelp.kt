package io.legado.app.help.storage

import android.os.Environment
import io.legado.app.utils.FileUtils
import java.io.File

/**
 * 公共下载目录辅助类
 *
 * 在安卓默认公共下载区域 /storage/emulated/0/Download/ 下维护本应用的专属目录结构：
 *   Download/legado/
 *       ├── Download/   存放下载的小说
 *       └── backup/     存放备份文件
 */
object ExternalStorageHelp {

    /** 公共 Download 根目录：/storage/emulated/0/Download */
    private val publicDownloadDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 应用根目录：/storage/emulated/0/Download/legado */
    val appRootDir: File
        get() = FileUtils.createFolderIfNotExist(publicDownloadDir, "legado")

    /** 小说下载目录：/storage/emulated/0/Download/legado/Download */
    val novelDownloadDir: File
        get() = FileUtils.createFolderIfNotExist(appRootDir, "Download")

    /** 备份文件目录：/storage/emulated/0/Download/legado/backup */
    val backupDir: File
        get() = FileUtils.createFolderIfNotExist(appRootDir, "backup")

    /**
     * 创建应用专属目录结构（legado/Download 与 legado/backup）
     * 如已存在则保持原样，不报错。
     */
    fun ensureAppDirs() {
        runCatching {
            novelDownloadDir
            backupDir
        }
    }

    /**
     * 扫描 backup 目录，返回所有备份压缩文件（.zip）
     * 按修改时间倒序排列（最新的在前）。
     */
    fun listBackupZipFiles(): List<File> {
        val dir = backupDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
