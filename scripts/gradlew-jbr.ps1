param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$javaHome = "C:\Program Files\Android\Android Studio\jbr"
$javaExe = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path $gradleWrapper)) {
    throw "Could not find gradle wrapper at '$gradleWrapper'."
}

if (-not (Test-Path $javaExe)) {
    throw "Could not find Android Studio JBR at '$javaExe'. Update scripts/gradlew-jbr.ps1 to match your installation."
}

$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @(":app:help")
}

Push-Location $repoRoot
try {
    & $gradleWrapper @GradleArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
