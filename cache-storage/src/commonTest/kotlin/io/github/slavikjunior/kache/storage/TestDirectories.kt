package io.github.slavikjunior.kache.storage

/**
 * A fresh, empty directory for one test, as a path.
 *
 * The engine takes a path `String`, so a test does not need platform file types — only a way
 * to name a directory the process may write to. Each call returns a different directory and
 * creates it, because a test that reuses a path makes itself pass once and fail on the second
 * run: the simulator's Caches directory outlives a process, and a leftover record looks like
 * data the test wrote.
 *
 * @param name Included in the path so a failing test names its own directory in a log.
 */
internal expect fun newTestDirectory(name: String): String
