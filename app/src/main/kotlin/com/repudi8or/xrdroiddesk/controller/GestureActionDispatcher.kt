package com.repudi8or.xrdroiddesk.controller

class GestureActionDispatcher(
    private val controller: DesktopController,
) {
    fun triggerClick(
        x: Float,
        y: Float,
    ) {
        controller.perform(DesktopAction.Click(x = x, y = y))
    }
}
