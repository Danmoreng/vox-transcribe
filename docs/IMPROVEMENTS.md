\# 📄 Requirements Document: Android Local Transcription Note-Taking App



\## 1. Projektkontext \& aktueller Stand



Die App ist eine Android-basierte Notizen-App (Note-taking), die Sprache lokal auf dem Gerät (On-Device) transkribiert.



\* \*\*Alte Implementierung:\*\* Android interne Speech-API (wurde entfernt/ersetzt aufgrund von Lücken bei der kontinuierlichen Transkription über Loops).

\* \*\*Aktuelle Implementierung:\*\* Custom C++ Bibliothek ("Voxtral" / ggf. basierend auf whisper.cpp) für Realtime-Transkription.

\* \*\*Aktuelle Herausforderung:\*\* Die Inferenz auf der CPU ist langsamer als Echtzeit (Faktor 3:1). Die UI und der State-Flow für das Laden der Modelle sind noch unübersichtlich.



\## 2. UI \& UX Anforderungen



\### 2.1 Globale Debug- \& Statusleiste (Development Only)



Es muss eine durchgängige Statusleiste implementiert werden, die über der gesamten App (Startscreen, Recording Screen etc.) sichtbar ist. Da sich die App im Entwicklungsstadium befindet, ist diese Leiste temporär und wird vor Release entfernt (bitte modular aufbauen).



\* \*\*Sichtbare Informationen:\*\*

\* Aktuell ausgewähltes Modell.

\* Lade-Status des Modells (z. B. "Nicht geladen", "Lädt gerade...", "Geladen im RAM").





\* \*\*Interaktionen in der Leiste:\*\*

\* Direktes Dropdown/Menü zum Wechseln des Modells.

\* Schnellzugriff/Button zu den globalen App-Einstellungen.







\### 2.2 Screen-Flow Optimierung



\* \*\*Startscreen:\*\* Bleibt primär eine Liste der bereits aufgenommenen Recordings.

\* \*\*Recording Screen:\*\* Das automatische Laden des Modells muss zuverlässig beim Betreten des Recording-Screens oder direkt beim App-Start erfolgen, ohne dass der Umweg über das Einstellungsmenü zwingend erforderlich ist.



\## 3. Transkriptions-Logik \& Performance



\### 3.1 Sequentielle Abarbeitung (Kein Audio-Skipping)



\* \*\*Aktuelles Verhalten:\*\* Um "Live" zu bleiben, werden bei langsamer CPU-Inferenz Audio-Chunks übersprungen (Skipping).

\* \*\*Neues Verhalten:\*\* Das Skipping muss vollständig deaktiviert werden. Die einkommenden Audio-Chunks müssen in eine chronologische Queue gelegt und strikt nacheinander abgearbeitet werden. Die Transkription hinkt dadurch zwar der Echtzeit hinterher, bleibt aber vollständig und akkurat.



\### 3.2 Post-Processing entfernen



\* \*\*Aktuelles Verhalten:\*\* Nach der Live-Transkription wird eine komplette Full-Text-Transkription als Nachbearbeitung gestartet.

\* \*\*Neues Verhalten:\*\* Dieser Post-Processing-Schritt soll komplett entfernt werden, da durch die neue sequentielle Abarbeitung der Live-Transkription (siehe 3.1) bereits der vollständige Text erfasst wird.



\## 4. Debugging \& Testing (Entwickler-Werkzeuge)



\### 4.1 Hardware-Beschleunigung Toggle (CPU / Vulkan / OpenCL)



Die C++ Bibliothek unterstützt theoretisch Hardwarebeschleunigung, Vulkan führte zuletzt jedoch zu Abstürzen.



\* \*\*Anforderung:\*\* Einbau von Toggles in den Einstellungen (oder der Debug-Leiste), um die Inference-Engine explizit zur Laufzeit zu wechseln (Fallback auf CPU, Testen von Vulkan, Testen von OpenCL).

\* \*\*Error Handling:\*\* Wenn das Modell mit Vulkan/OpenCL initialisiert wird und crasht (z. B. Segfault oder Exception aus der C++ JNI Bridge), muss dies bestmöglich abgefangen und in der UI geloggt werden, anstatt dass die gesamte App abstürzt.



\### 4.2 Wiederverwendbare Test-Audio-Funktion



Um das Testen der Transkriptions-Engines zu beschleunigen, muss eine Mock-Recording-Funktion implementiert werden.



\* \*\*Anforderung:\*\* Die Möglichkeit, eine Testnachricht (Audio) einmalig aufzunehmen (oder eine bestehende Audio-Datei zu laden) und diese auf Knopfdruck immer wieder an die Transkriptions-Pipeline zu füttern.

\* \*\*Nutzen:\*\* Erlaubt verlässliche A/B-Tests für Inferenzgeschwindigkeit zwischen CPU und Vulkan mit exakt demselben Audio-Input, ohne jedes Mal neu sprechen zu müssen.



