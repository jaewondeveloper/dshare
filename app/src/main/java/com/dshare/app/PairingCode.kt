package com.dshare.app

import kotlin.random.Random

object PairingCode {
    fun generate(): String {
        val n = Random.nextInt(0, 1_000_000)
        return n.toString().padStart(6, '0')
    }
}
