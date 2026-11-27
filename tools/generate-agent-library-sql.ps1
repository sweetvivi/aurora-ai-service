<#
.SYNOPSIS
    Generate the Flyway seed migration for the AI-IDE-Agent prompt library.

.DESCRIPTION
    Every Markdown file in https://github.com/bjlida/AI-IDE-Agent is a complete agent definition:

        ---
        name: python-pro
        description: <one-line summary shown as the agent intro>
        model: sonnet
        ---

        <system prompt body>

Mapping:
        containing directory        -> agent_categories.category_key
        file name without .md       -> agents.name
        front matter `name`         -> agents.agent_key and agents.sn
        front matter `description`  -> agents.description  (empty string when absent)
        front matter `model`        -> agents.source_model
        body after the front matter -> agents.system_prompt

    Category keys and display names live in agent-library-categories.json next to this script, so
    this file stays ASCII only: Windows PowerShell 5.1 reads BOM-less .ps1 files with the ANSI code
    page and would mangle literal Chinese. JSON is read explicitly as UTF-8 instead.

    The generated SQL is idempotent (ON DUPLICATE KEY UPDATE) and can be re-run.

.EXAMPLE
    .\tools\generate-agent-library-sql.ps1

.EXAMPLE
    .\tools\generate-agent-library-sql.ps1 -LibraryRoot D:\hub\AI-IDE-Agent -Version V10
#>
[CmdletBinding()]
param(
    [string]$LibraryRoot = 'D:\hub\AI-IDE-Agent',
    [string]$Version = 'V10',
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot "..\src\main\resources\db\migration\${Version}__seed_agent_library.sql"
}

if (-not (Test-Path -LiteralPath $LibraryRoot)) {
    throw "Prompt library directory not found: $LibraryRoot"
}

$manifestPath = Join-Path $PSScriptRoot 'agent-library-categories.json'
$parsedCategories = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$categories = @()
foreach ($item in $parsedCategories) {
    $categories += $item
}

function ConvertTo-SqlLiteral {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value) { return '' }

    # Both the backslash and the quote need escaping: prompts contain regexes, escape sequences and
    # code snippets.
    return $Value.Replace('\', '\\').Replace("'", "''")
}

function Read-AgentDefinition {
    param([System.IO.FileInfo]$File)

    $lines = @(Get-Content -LiteralPath $File.FullName -Encoding UTF8)
    if ($lines.Count -lt 3 -or $lines[0].Trim() -ne '---') {
        throw "Missing YAML front matter: $($File.FullName)"
    }

    $end = -1
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq '---') { $end = $i; break }
    }
    if ($end -lt 0) {
        throw "Unterminated YAML front matter: $($File.FullName)"
    }

    $meta = @{}
    foreach ($line in $lines[1..($end - 1)]) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*)$') {
            $meta[$Matches[1]] = $Matches[2].Trim().Trim('"')
        }
    }

    if (-not $meta['name']) {
        throw "Front matter without a name: $($File.FullName)"
    }

    $body = ''
    if ($end + 1 -le $lines.Count - 1) {
        $body = ($lines[($end + 1)..($lines.Count - 1)] -join "`n").Trim()
    }

    $description = ''
    if ($meta.ContainsKey('description')) { $description = $meta['description'] }
    $sourceModel = ''
    if ($meta.ContainsKey('model')) { $sourceModel = $meta['model'] }

    [pscustomobject]@{
        Key         = $meta['name']
        Name        = [System.IO.Path]::GetFileNameWithoutExtension($File.Name)
        Description = $description
        SourceModel = $sourceModel
        SourcePath  = $File.FullName.Substring($LibraryRoot.Length).TrimStart('\', '/').Replace('\', '/')
        Prompt      = $body
    }
}

# ------------------------------------------------------------------------------- collect

$definitions = New-Object System.Collections.Generic.List[object]
$categoryRows = New-Object System.Collections.Generic.List[object]

foreach ($category in $categories) {
    $dir = Join-Path $LibraryRoot $category.folder
    if (-not (Test-Path -LiteralPath $dir)) {
        throw "Category directory not found: $dir"
    }

    $files = @(Get-ChildItem -LiteralPath $dir -File -Filter *.md | Sort-Object Name)
    $categoryRows.Add([pscustomobject]@{
        Key   = $category.key
        Name  = $category.name
        Sort  = [int]$category.sort
        Count = $files.Count
    })

    $index = 0
    foreach ($file in $files) {
        $index++
        $definition = Read-AgentDefinition -File $file
        $definition | Add-Member -NotePropertyName Category -NotePropertyValue $category.key
        $definition | Add-Member -NotePropertyName SortOrder -NotePropertyValue ($index * 10)
        $definitions.Add($definition)
    }
}

$duplicates = @($definitions | Group-Object Key | Where-Object { $_.Count -gt 1 })
if ($duplicates.Count -gt 0) {
    throw "Duplicate agent_key: $(($duplicates | ForEach-Object { $_.Name }) -join ', ')"
}

# ------------------------------------------------------------------------------- emit

$out = New-Object System.Text.StringBuilder
$generatedOn = (Get-Date).ToString('yyyy-MM-dd')

[void]$out.AppendLine("-- ${Version}: seed the AI-IDE-Agent prompt library as agent rows.")
[void]$out.AppendLine('--')
[void]$out.AppendLine('-- Source: https://github.com/bjlida/AI-IDE-Agent')
[void]$out.AppendLine("-- Generated by tools/generate-agent-library-sql.ps1 on ${generatedOn}.")
[void]$out.AppendLine('-- Do not hand-edit this file; change the library or the generator and regenerate.')
[void]$out.AppendLine('--')
[void]$out.AppendLine("-- $($definitions.Count) agents in $($categoryRows.Count) categories:")
foreach ($row in $categoryRows) {
    [void]$out.AppendLine("--   $($row.Key.PadRight(18)) $($row.Name) ($($row.Count))")
}
[void]$out.AppendLine('--')
[void]$out.AppendLine('-- The library carries prompts only, so icon_url, agent_tags, agent_abilities and')
[void]$out.AppendLine('-- agent_models stay empty until an operator fills them in. descriptions come from the')
[void]$out.AppendLine('-- front matter and are left as an empty string when the source has none.')
[void]$out.AppendLine('')
[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('-- Categories')
[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('')

foreach ($row in $categoryRows) {
    [void]$out.AppendLine('INSERT INTO agent_categories (site_id, category_key, name, sort_order, status)')
    [void]$out.AppendLine("SELECT s.id, '$(ConvertTo-SqlLiteral $row.Key)', '$(ConvertTo-SqlLiteral $row.Name)', $($row.Sort), 'ACTIVE'")
    [void]$out.AppendLine('FROM app_sites s')
    [void]$out.AppendLine("WHERE s.site_key = 'default'")
    [void]$out.AppendLine('ON DUPLICATE KEY UPDATE')
    [void]$out.AppendLine('    name = VALUES(name),')
    [void]$out.AppendLine('    sort_order = VALUES(sort_order),')
    [void]$out.AppendLine('    status = VALUES(status);')
    [void]$out.AppendLine('')
}

$currentCategory = $null
foreach ($definition in $definitions) {
    if ($definition.Category -ne $currentCategory) {
        $currentCategory = $definition.Category
        $label = ($categoryRows | Where-Object { $_.Key -eq $currentCategory }).Name
        [void]$out.AppendLine('-- ---------------------------------------------------------------------------')
        [void]$out.AppendLine("-- $label")
        [void]$out.AppendLine('-- ---------------------------------------------------------------------------')
        [void]$out.AppendLine('')
    }

    [void]$out.AppendLine("-- $($definition.SourcePath)")
    [void]$out.AppendLine('INSERT INTO agents (site_id, category_id, agent_key, sn, agent_type, name, description,')
    [void]$out.AppendLine('                    system_prompt, source_model, source_path, status, sort_order,')
    [void]$out.AppendLine('                    publish_status, visibility)')
    [void]$out.AppendLine("SELECT s.id, c.id, '$(ConvertTo-SqlLiteral $definition.Key)', '$(ConvertTo-SqlLiteral $definition.Key)', 'OFFICIAL',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Name)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Description)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Prompt)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.SourceModel)', '$(ConvertTo-SqlLiteral $definition.SourcePath)',")
    [void]$out.AppendLine("       'ACTIVE', $($definition.SortOrder), 'PUBLISHED', 'PUBLIC'")
    [void]$out.AppendLine('FROM app_sites s')
    [void]$out.AppendLine("JOIN agent_categories c ON c.site_id = s.id AND c.category_key = '$(ConvertTo-SqlLiteral $definition.Category)'")
    [void]$out.AppendLine("WHERE s.site_key = 'default'")
    [void]$out.AppendLine('ON DUPLICATE KEY UPDATE')
    [void]$out.AppendLine('    category_id = VALUES(category_id),')
    [void]$out.AppendLine('    agent_type = VALUES(agent_type),')
    [void]$out.AppendLine('    name = VALUES(name),')
    [void]$out.AppendLine('    description = VALUES(description),')
    [void]$out.AppendLine('    system_prompt = VALUES(system_prompt),')
    [void]$out.AppendLine('    source_model = VALUES(source_model),')
    [void]$out.AppendLine('    source_path = VALUES(source_path),')
    [void]$out.AppendLine('    status = VALUES(status),')
    [void]$out.AppendLine('    sort_order = VALUES(sort_order),')
    [void]$out.AppendLine('    publish_status = VALUES(publish_status),')
    [void]$out.AppendLine('    visibility = VALUES(visibility);')
    [void]$out.AppendLine('')
}

$resolved = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText($resolved, $out.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Wrote $resolved"
Write-Host "Agents: $($definitions.Count); categories: $($categoryRows.Count)"
