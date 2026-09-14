package com.jhony4lves.echo360.domain.fix

/** Internal bridge used by EchoFix repair code. */
internal object XboxPath {
    fun canonical(value: String): String =
        com.jhony4lves.echo360.domain.xbox.XboxPath.canonical(value)
}
