[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path $HOME ".android-keys/obsidelta-release.p12"),
    [string]$PropertiesPath,
    [string]$Alias = "obsidelta",
    [ValidateRange(24, 128)]
    [int]$PasswordLength = 32,
    [switch]$GeneratePasswordOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-CryptoRandomIndex([int]$UpperBound) {
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $buffer = New-Object byte[] 1
        $limit = 256 - (256 % $UpperBound)
        do {
            $generator.GetBytes($buffer)
        } while ($buffer[0] -ge $limit)
        return $buffer[0] % $UpperBound
    } finally {
        $generator.Dispose()
    }
}

function New-CryptoPassword([int]$Length) {
    $upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    $lower = "abcdefghijkmnopqrstuvwxyz"
    $digits = "23456789"
    $symbols = "@%_-"
    $alphabet = $upper + $lower + $digits + $symbols

    $characters = [System.Collections.Generic.List[char]]::new()
    foreach ($required in @($upper, $lower, $digits, $symbols)) {
        $characters.Add($required[(Get-CryptoRandomIndex $required.Length)])
    }
    while ($characters.Count -lt $Length) {
        $characters.Add($alphabet[(Get-CryptoRandomIndex $alphabet.Length)])
    }
    for ($index = $characters.Count - 1; $index -gt 0; $index--) {
        $swapWith = Get-CryptoRandomIndex ($index + 1)
        $temporary = $characters[$index]
        $characters[$index] = $characters[$swapWith]
        $characters[$swapWith] = $temporary
    }
    return -join $characters
}

function Read-SigningProperties([string]$Path) {
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim().Trim('"').Trim("'")
            $values[$name] = $value
        }
    }
    return $values
}

if ($GeneratePasswordOnly) {
    New-CryptoPassword $PasswordLength
    return
}

$repositoryRoot = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($PropertiesPath)) {
    $PropertiesPath = Join-Path $repositoryRoot "secret.properties"
}
$KeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)
$PropertiesPath = [System.IO.Path]::GetFullPath($PropertiesPath)

$storePassword = $null
$keyPassword = $null
$writeProperties = $true
if (Test-Path -LiteralPath $PropertiesPath) {
    $existing = Read-SigningProperties $PropertiesPath
    $requiredNames = @(
        "ANDROID_KEYSTORE_PATH",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD"
    )
    $missingValues = @($requiredNames | Where-Object {
        -not $existing.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($existing[$_])
    })
    $hasAllValues = $missingValues.Count -eq 0
    $containsTemplateValues = ($existing.Values -join "|") -match "replace-with|your-name"

    if ($hasAllValues -and -not $containsTemplateValues) {
        $configuredPath = $existing["ANDROID_KEYSTORE_PATH"]
        $KeystorePath = if ([System.IO.Path]::IsPathRooted($configuredPath)) {
            [System.IO.Path]::GetFullPath($configuredPath)
        } else {
            [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $configuredPath))
        }
        $storePassword = $existing["ANDROID_KEYSTORE_PASSWORD"]
        $Alias = $existing["ANDROID_KEY_ALIAS"]
        $keyPassword = $existing["ANDROID_KEY_PASSWORD"]
        $writeProperties = $false
    } elseif ($containsTemplateValues) {
        $templateBackup = "$PropertiesPath.template-backup"
        Copy-Item -LiteralPath $PropertiesPath -Destination $templateBackup -Force
    } else {
        throw "Existing properties file is incomplete: $PropertiesPath"
    }
}

$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($null -ne $keytoolCommand) { $keytoolCommand.Source } else { $null }
if ($null -eq $keytoolPath -and -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME "bin/keytool.exe"
    if (Test-Path -LiteralPath $candidate) {
        $keytoolPath = $candidate
    }
}
if ($null -eq $keytoolPath) {
    throw "keytool was not found. Install JDK 21 or add JAVA_HOME/bin to PATH."
}

if (Test-Path -LiteralPath $KeystorePath) {
    if ($null -eq $storePassword) {
        throw "Keystore already exists but no valid credentials were found: $KeystorePath"
    }
    $env:OBSIDELTA_STORE_PASSWORD = $storePassword
    try {
        & $keytoolPath -list -keystore $KeystorePath `
            "-storepass:env" OBSIDELTA_STORE_PASSWORD `
            -alias $Alias | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Existing keystore verification failed with exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item Env:OBSIDELTA_STORE_PASSWORD -ErrorAction SilentlyContinue
    }
    [pscustomobject]@{
        Keystore = $KeystorePath
        Properties = $PropertiesPath
        Alias = $Alias
        Existing = $true
        NextCommand = ".\gradlew.bat :androidApp:assembleRelease :androidApp:bundleRelease"
    }
    return
}

New-Item -ItemType Directory -Force (Split-Path $KeystorePath -Parent) | Out-Null
New-Item -ItemType Directory -Force (Split-Path $PropertiesPath -Parent) | Out-Null

$generatedPassword = $null
if ($null -eq $storePassword) {
    $generatedPassword = New-CryptoPassword $PasswordLength
    $storePassword = $generatedPassword
    $keyPassword = $generatedPassword
}
$env:OBSIDELTA_STORE_PASSWORD = $storePassword
$env:OBSIDELTA_KEY_PASSWORD = $keyPassword
try {
    & $keytoolPath -genkeypair -v `
        -storetype PKCS12 `
        -keystore $KeystorePath `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=ObsiDelta Sync, O=ObsiDelta Sync, C=RU" `
        "-storepass:env" OBSIDELTA_STORE_PASSWORD `
        "-keypass:env" OBSIDELTA_KEY_PASSWORD `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:OBSIDELTA_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:OBSIDELTA_KEY_PASSWORD -ErrorAction SilentlyContinue
}

if ($writeProperties) {
    $propertiesKeystorePath = $KeystorePath.Replace("\", "/")
    $properties = @(
        "ANDROID_KEYSTORE_PATH=$propertiesKeystorePath"
        "ANDROID_KEYSTORE_PASSWORD=$storePassword"
        "ANDROID_KEY_ALIAS=$Alias"
        "ANDROID_KEY_PASSWORD=$keyPassword"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText(
        $PropertiesPath,
        $properties + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
}

[pscustomobject]@{
    Keystore = $KeystorePath
    Properties = $PropertiesPath
    Alias = $Alias
    Existing = $false
    GeneratedPassword = ($null -ne $generatedPassword)
    NextCommand = ".\gradlew.bat :androidApp:assembleRelease :androidApp:bundleRelease"
}
