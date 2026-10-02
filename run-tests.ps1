<#
.SYNOPSIS
    Ejecuta la suite de tests unitarios de PackForge en Windows.

.DESCRIPTION
    El Gradle Test Executor hereda el PATH del proceso y Gradle lo inyecta en
    el argumento -cp del proceso hijo. Si el PATH contiene rutas CON ESPACIOS
    (p.ej. "C:\Users\<user>\AppData\Local\Programs\Microsoft VS Code\bin"), las
    entradas se parten sin comillas y la JVM interpreta "VS" como el nombre de
    la clase principal:

        Error: no se ha encontrado o cargado la clase principal VS
        java.lang.ClassNotFoundException: VS

    El runner aborta ANTES de ejecutar un solo test, por lo que la suite completa
    se declara fallida y los tests de regresion de TICKET-CORE-001/002 nunca
    corren. Este script sanea el PATH del proceso actual ANTES de invocar Gradle,
    de modo que el runner nace con un PATH sin espacios.

    Es la unica solucion fiable: configurar esto desde build.gradle.kts no
    funciona porque el fallo ocurre al construir el comando del ejecutor, antes
    de que el Test task reciba configuracion.

.PARAMETER Task
    Tarea de Gradle a ejecutar. Por defecto la suite completa.

.PARAMETER Filter
    Filtro opcional --tests (p.ej. "com.packforge.app.domain.engine.*").

.EXAMPLE
    .\run-tests.ps1
    .\run-tests.ps1 -Task :app:compileDebugKotlin
    .\run-tests.ps1 -Filter "com.packforge.app.domain.engine.ResourcePathRegistryMutationTest"
#>
[CmdletBinding()]
param(
    [string]$Task = ':app:testDebugUnitTest',
    [string]$Filter = ''
)

$ErrorActionPreference = 'Continue'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# Elimina SOLO las entradas del PATH que contienen espacios: son las que Gradle
# concatena sin comillas en el classpath del runner de tests.
$originalPath = $env:PATH
$cleanEntries = @(
    $env:PATH -split [regex]::Escape([IO.Path]::PathSeparator) |
        Where-Object { $_ -and -not $_.Contains(' ') }
)
$env:PATH = ($cleanEntries -join [IO.Path]::PathSeparator)

$removed = @(
    ($originalPath -split [regex]::Escape([IO.Path]::PathSeparator)) |
        Where-Object { $_ -and $_.Contains(' ') }
)

Write-Host ''
Write-Host '=============================================================='
Write-Host ' PackForge - suite de tests unitarios'
Write-Host '=============================================================='
Write-Host " PATH: $($cleanEntries.Count) entradas limpias"
if ($removed.Count -gt 0) {
    Write-Host " Descartadas por contener espacios:"
    $removed | ForEach-Object { Write-Host "   - $_" }
} else {
    Write-Host ' (sin entradas con espacios que descartar)'
}
Write-Host " Tarea: $Task"
if ($Filter) { Write-Host " Filtro: $Filter" }
Write-Host ''

$gradlew = Join-Path $projectRoot 'gradlew.bat'
$args = @($Task, '--console=plain')
if ($Filter) { $args += @('--tests', $Filter) }

Push-Location $projectRoot
try {
    & $gradlew @args
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

Write-Host ''
if ($exitCode -eq 0) {
    Write-Host '[OK] Suite completada sin fallos.'
} else {
    Write-Host "[ERROR] La suite termino con codigo $exitCode."
}
exit $exitCode