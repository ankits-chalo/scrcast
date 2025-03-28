package dev.chalo.scrcast.app.list

class StateCallbackActivity : MainActivity() {

    override fun onResume() {
        super.onResume()
        recorder.onRecordingStateChange { state -> handleRecorderState(state) }
    }

    override fun onStop() {
        super.onStop()
        recorder.onRecordingStateChange { }
    }
}
