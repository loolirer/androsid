package com.androsid.actuators

import org.json.JSONObject

interface Actuator {
    val command: String
    fun handle(args: JSONObject)
}
