// Minimal reproduction: a long-running command that evaluates tasks repeatedly
// (as Play's `run` does on every reload) gets slower each time, because the
// command's TaskProgress keeps anonOwners / calledBy / taskNameCache entries
// for every task it has ever seen.
ThisBuild / scalaVersion := "3.7.4"

// 30 small subprojects, so each evaluation of root/Compile/compile is a few
// thousand tasks, like a modest multi-module Play app.
lazy val m1 = project in file("modules/m1")
lazy val m2 = project in file("modules/m2")
lazy val m3 = project in file("modules/m3")
lazy val m4 = project in file("modules/m4")
lazy val m5 = project in file("modules/m5")
lazy val m6 = project in file("modules/m6")
lazy val m7 = project in file("modules/m7")
lazy val m8 = project in file("modules/m8")
lazy val m9 = project in file("modules/m9")
lazy val m10 = project in file("modules/m10")
lazy val m11 = project in file("modules/m11")
lazy val m12 = project in file("modules/m12")
lazy val m13 = project in file("modules/m13")
lazy val m14 = project in file("modules/m14")
lazy val m15 = project in file("modules/m15")
lazy val m16 = project in file("modules/m16")
lazy val m17 = project in file("modules/m17")
lazy val m18 = project in file("modules/m18")
lazy val m19 = project in file("modules/m19")
lazy val m20 = project in file("modules/m20")
lazy val m21 = project in file("modules/m21")
lazy val m22 = project in file("modules/m22")
lazy val m23 = project in file("modules/m23")
lazy val m24 = project in file("modules/m24")
lazy val m25 = project in file("modules/m25")
lazy val m26 = project in file("modules/m26")
lazy val m27 = project in file("modules/m27")
lazy val m28 = project in file("modules/m28")
lazy val m29 = project in file("modules/m29")
lazy val m30 = project in file("modules/m30")

lazy val root = (project in file("."))
  .dependsOn(m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17, m18, m19, m20, m21, m22, m23, m24, m25, m26, m27, m28, m29, m30)
  .aggregate(m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17, m18, m19, m20, m21, m22, m23, m24, m25, m26, m27, m28, m29, m30)

// `loop <iterations> [clear|clearall]`: one command that runs root/Compile/compile N
// times, printing how long each evaluation took, the heap in use after a GC,
// and the sizes of the command's TaskProgress maps. With `clear`, those maps
// are emptied (by reflection) after each evaluation, as a fix would do;
// `clearall` also closes and empties TaskProgress.active.
commands += Command.args("loop", "<iterations> [clear|clearall]") { (state, args) =>
  val iterations = args.head.toInt
  val clear = args.contains("clear") || args.contains("clearall")
  val clearActive = args.contains("clearall")
  val extracted = Project.extract(state)
  val rt = java.lang.Runtime.getRuntime
  val progress = state.attributes.entries.map(_.value)
    .find(_.getClass.getName == "sbt.internal.TaskProgress").get
  def maps: Seq[(String, java.util.Map[?, ?])] =
    Iterator.iterate[Class[?]](progress.getClass)(_.getSuperclass).takeWhile(_ != null)
      .flatMap(_.getDeclaredFields)
      .filter(f => classOf[java.util.Map[?, ?]].isAssignableFrom(f.getType))
      .map { f => f.setAccessible(true); f.getName.split("\\$").last -> f.get(progress).asInstanceOf[java.util.Map[?, ?]] }
      .toList
  (1 to iterations).foreach { i =>
    val t0 = System.nanoTime
    extracted.runTask(root / Compile / compile, state)
    val ms = (System.nanoTime - t0) / 1000000
    val sizes = maps.map { case (n, m) => s"$n=${m.size}" }.mkString(" ")
    maps.foreach { case (n, m) =>
      if (clear && Set("anonOwners", "calledBy", "taskNameCache")(n)) m.clear()
      if (clearActive && n == "active") {
        m.values.forEach(_.asInstanceOf[AutoCloseable].close())
        m.clear()
      }
    }
    System.gc()
    val heapMb = (rt.totalMemory - rt.freeMemory) / 1024 / 1024
    println(f"iteration $i%3d: $ms%5d ms, heap after GC $heapMb%4d MB, $sizes")
  }
  state
}
