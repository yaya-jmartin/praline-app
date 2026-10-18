package com.yaya.praline

import java.io.File

/** A repository file, whether the tests run from the repo root (hand build) or from agp/app (GitHub build). */
fun repoFile(path: String): File {
    val here = File(path)
    return if (here.exists()) here else File("../../$path")
}
