package com.repudi8or.xrdroiddesk.controller

sealed class DesktopAction {
    data class Click(
        val x: Float,
        val y: Float,
    ) : DesktopAction()
}
