package com.comics8.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalTargetResolverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolvesDirectZipFile() {
        val zip = tempFolder.newFile("manga.zip")
        val target = ExternalTargetResolver.resolve(zip)
        assertThat(target).isInstanceOf(ExternalTargetResolver.Target.Zip::class.java)
        assertThat((target as ExternalTargetResolver.Target.Zip).file).isEqualTo(zip)
    }

    @Test
    fun resolvesMountedVolumeWithSameNameZipInside() {
        // Simulates macOS SMB volume mount where mount point is /Volumes/Comic.zip and inside is Comic.zip
        val mountDir = tempFolder.newFolder("Comic.zip")
        val innerZip = File(mountDir, "Comic.zip").apply { createNewFile() }

        val target = ExternalTargetResolver.resolve(mountDir)
        assertThat(target).isInstanceOf(ExternalTargetResolver.Target.Zip::class.java)
        assertThat((target as ExternalTargetResolver.Target.Zip).file).isEqualTo(innerZip)
    }

    @Test
    fun resolvesDirectoryWithSingleArchive() {
        val dir = tempFolder.newFolder("folderWithZip")
        val innerCbz = File(dir, "volume1.cbz").apply { createNewFile() }

        val target = ExternalTargetResolver.resolve(dir)
        assertThat(target).isInstanceOf(ExternalTargetResolver.Target.Zip::class.java)
        assertThat((target as ExternalTargetResolver.Target.Zip).file).isEqualTo(innerCbz)
    }

    @Test
    fun resolvesDirectoryWithImages() {
        val dir = tempFolder.newFolder("imagesFolder")
        File(dir, "001.jpg").apply { createNewFile() }
        File(dir, "002.png").apply { createNewFile() }

        val target = ExternalTargetResolver.resolve(dir)
        assertThat(target).isInstanceOf(ExternalTargetResolver.Target.Folder::class.java)
        val folderTarget = target as ExternalTargetResolver.Target.Folder
        assertThat(folderTarget.dir).isEqualTo(dir)
        assertThat(folderTarget.initialFile).isNull()
    }

    @Test
    fun resolvesSingleImageToParentFolderWithInitialFile() {
        val dir = tempFolder.newFolder("chapter")
        val img1 = File(dir, "001.jpg").apply { createNewFile() }
        File(dir, "002.jpg").apply { createNewFile() }

        val target = ExternalTargetResolver.resolve(img1)
        assertThat(target).isInstanceOf(ExternalTargetResolver.Target.Folder::class.java)
        val folderTarget = target as ExternalTargetResolver.Target.Folder
        assertThat(folderTarget.dir).isEqualTo(dir)
        assertThat(folderTarget.initialFile).isEqualTo(img1)
    }

    @Test
    fun returnsNullForNonExistentOrUnsupportedFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist.zip")
        assertThat(ExternalTargetResolver.resolve(nonExistent)).isNull()

        val textFile = tempFolder.newFile("document.txt")
        assertThat(ExternalTargetResolver.resolve(textFile)).isNull()

        val emptyDir = tempFolder.newFolder("emptyFolder")
        assertThat(ExternalTargetResolver.resolve(emptyDir)).isNull()
    }
}
