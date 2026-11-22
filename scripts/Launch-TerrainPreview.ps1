[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Seed,
    [ValidateSet('creative', 'survival')]
    [string] $GameMode,
    [string] $Config
)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
if (-not $Config) { $Config = Join-Path $project 'run/minecraft-preview.properties' }

function Read-Settings([string] $Path) {
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -notmatch '^\s*([\w.-]+)\s*=\s*(.*?)\s*$' -or $settings.ContainsKey($Matches[1])) {
            throw "Invalid or duplicate setting in $Path"
        }
        $settings[$Matches[1]] = $Matches[2]
    }
    return $settings
}

function Require-Path([string] $Key, [string] $Kind) {
    $value = $settings[$Key]
    if (-not $value -or -not (Test-Path -LiteralPath $value -PathType $Kind)) {
        throw "Set $Key to an existing path in $Config"
    }
    return (Resolve-Path -LiteralPath $value).Path
}

try {
    if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
        throw "Create $Config using scripts/minecraft-preview.example.properties."
    }
    $settings = Read-Settings $Config
    if (-not $PSBoundParameters.ContainsKey('Seed')) {
        $Seed = if ($settings.seed) { $settings.seed } else { '42' }
    }
    if (-not $GameMode) {
        $GameMode = if ($settings.gameMode) { $settings.gameMode } else { 'creative' }
    }
    $number = 0L
    if ($Seed -notmatch '^[+-]?[0-9]+$' -or -not [long]::TryParse($Seed, [ref] $number)) {
        throw 'Seed must be a signed 64-bit integer.'
    }
    if ($GameMode -notin 'creative', 'survival') { throw 'gameMode must be creative or survival.' }
    $GameMode = $GameMode.ToLowerInvariant()
    $gameDir = Require-Path 'game.dir' 'Container'
    $launchFile = Require-Path 'launch.file' 'Leaf'
    if ([IO.Path]::GetExtension($launchFile) -notin '.bat', '.cmd', '.exe') {
        throw 'launch.file must be a Windows .bat, .cmd or .exe for this game instance.'
    }

    $expected = (Read-Settings (Join-Path $project 'gradle.properties')).minecraft_version
    if ($settings.'minecraft.version' -ne $expected) {
        throw "This BetterTerrain checkout targets Minecraft $expected. Check minecraft.version in $Config."
    }
    $profileId = Split-Path $gameDir -Leaf
    $profile = Get-Content -LiteralPath (Join-Path $gameDir "$profileId.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($profile.mainClass -ne 'net.fabricmc.loader.impl.launch.knot.KnotClient') {
        throw 'game.dir must point at a Fabric client instance.'
    }
    $version = $profile.inheritsFrom
    if (-not $version) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $gameDir "$profileId.jar"))
        try {
            $entry = $archive.GetEntry('version.json')
            if (-not $entry) { throw 'The installed Minecraft JAR has no version.json.' }
            $reader = New-Object IO.StreamReader($entry.Open())
            try { $version = ($reader.ReadToEnd() | ConvertFrom-Json).id }
            finally { $reader.Dispose() }
        } finally {
            $archive.Dispose()
        }
    }
    if ($version -ne $expected) { throw "The installed profile is Minecraft $version. Expected $expected." }

    if ($settings.'java.home') { $env:JAVA_HOME = Require-Path 'java.home' 'Container' }
    if ($settings.'gradle.userHome') { $env:GRADLE_USER_HOME = $settings.'gradle.userHome' }
    $gradleArgs = @('stageMinecraftPreview', '--console=plain')
    if ($settings.'gradle.offline' -eq 'true') { $gradleArgs += '--offline' }
    Push-Location -LiteralPath $project
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'BetterTerrain build failed.' }
    } finally {
        Pop-Location
    }

    $jar = Join-Path $project 'build/minecraft-preview-install/betterterrain-preview.jar'
    $mods = Join-Path $gameDir 'mods'
    $installed = Join-Path $mods 'betterterrain-preview.jar'
    $requestDir = Join-Path $project ('run/minecraft-preview/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $requestDir -Force | Out-Null
    if (-not (Test-Path -LiteralPath $installed -PathType Leaf) -or
        (Get-FileHash -LiteralPath $jar).Hash -ne (Get-FileHash -LiteralPath $installed).Hash) {
        [IO.Directory]::CreateDirectory($mods) | Out-Null
        $temporary = Join-Path $mods ([guid]::NewGuid().ToString('N') + '.tmp')
        try {
            Copy-Item -LiteralPath $jar -Destination $temporary
            if (Test-Path -LiteralPath $installed -PathType Leaf) {
                $backup = Join-Path $mods ("betterterrain-preview-" + [guid]::NewGuid().ToString('N') + '.jar.bak')
                [IO.File]::Replace($temporary, $installed, $backup)
                Write-Host "Previous BetterTerrain JAR saved to $backup"
            } else {
                [IO.File]::Move($temporary, $installed)
            }
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
        }
    }

    $requestFile = Join-Path $requestDir 'request.properties'
    $resultFile = Join-Path $requestDir 'result.txt'
    $numberText = $number.ToString([Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::WriteAllLines($requestFile, @("seed=$numberText", "gameMode=$GameMode"))
    $previousRequest = $env:BETTERTERRAIN_PREVIEW_REQUEST
    $env:BETTERTERRAIN_PREVIEW_REQUEST = $requestFile
    try {
        Start-Process -FilePath $launchFile -WorkingDirectory $gameDir -WindowStyle Normal
    } finally {
        $env:BETTERTERRAIN_PREVIEW_REQUEST = $previousRequest
    }

    Write-Host "Opening BetterTerrain seed $numberText ($GameMode)..."
    Write-Host "Launch report: $resultFile"
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while (-not (Test-Path -LiteralPath $resultFile -PathType Leaf)) {
        if ([DateTime]::UtcNow -ge $deadline) {
            throw 'Minecraft did not confirm world entry. Check the game window and logs/latest.log in game.dir.'
        }
        Start-Sleep -Milliseconds 500
    }
    $report = Get-Content -LiteralPath $resultFile -Raw
    if (-not $report.StartsWith('OK ')) { throw $report.Trim() }
    Write-Host $report.Trim()
} catch {
    Write-Error -ErrorAction Continue $_.Exception.Message
    exit 1
}
