package com.example.voxtranscribe.data

import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class EngineType {
    Voxtral, Google
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DynamicTranscriptionRepository @Inject constructor(
    private val voxtralRepo: VoxtralTranscriptionRepository,
    private val googleRepo: AndroidSpeechRecognizerImpl
) : TranscriptionRepository {

    private val _currentEngineType = MutableStateFlow(EngineType.Voxtral)
    val currentEngineType: StateFlow<EngineType> = _currentEngineType.asStateFlow()

    private val activeRepo: TranscriptionRepository
        get() = when (_currentEngineType.value) {
            EngineType.Voxtral -> voxtralRepo
            EngineType.Google -> googleRepo
        }

    override val transcriptionState: SharedFlow<LogEntry> = _currentEngineType.flatMapLatest { type ->
        when (type) {
            EngineType.Voxtral -> voxtralRepo.transcriptionState
            EngineType.Google -> googleRepo.transcriptionState
        }
    }.shareIn(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        started = SharingStarted.Eagerly,
        replay = 1
    )

    override val partialText: StateFlow<String> = _currentEngineType.flatMapLatest { type ->
        when (type) {
            EngineType.Voxtral -> voxtralRepo.partialText
            EngineType.Google -> googleRepo.partialText
        }
    }.stateIn(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = ""
    )

    override val isOfflineModel: StateFlow<Boolean> = _currentEngineType.flatMapLatest { type ->
        when (type) {
            EngineType.Voxtral -> voxtralRepo.isOfflineModel
            EngineType.Google -> googleRepo.isOfflineModel
        }
    }.stateIn(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = true
    )

    override val engineState: StateFlow<EngineState> = _currentEngineType.flatMapLatest { type ->
        when (type) {
            EngineType.Voxtral -> voxtralRepo.engineState
            EngineType.Google -> googleRepo.engineState
        }
    }.stateIn(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = EngineState.Uninitialized
    )

    fun setEngineType(type: EngineType) {
        if (_currentEngineType.value != type) {
            activeRepo.cleanup()
            _currentEngineType.value = type
            if (type == EngineType.Voxtral) {
                voxtralRepo.loadModel()
            }
        }
    }

    override fun startListening() {
        activeRepo.startListening()
    }

    override suspend fun stopListening() {
        activeRepo.stopListening()
    }

    override fun clear() {
        activeRepo.clear()
    }

    override fun cleanup() {
        activeRepo.cleanup()
    }

    override suspend fun transcribeTestAudio(): String {
        return activeRepo.transcribeTestAudio()
    }
}
