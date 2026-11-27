<#
.SYNOPSIS
    Generate the Flyway seed migration for the awesome-ai-persona-skills library.

.DESCRIPTION
    The source library contains skill-style Markdown files with YAML front matter and a
    director collection whose files start directly with a Markdown heading. This script
    normalizes both formats into the agents tables used by the catalogue.

    Mapping:
        source file / front matter name -> agents.agent_key and agents.sn
        source title                   -> agents.name
        front matter description       -> agents.description
        Markdown body                  -> agents.system_prompt
        category manifest              -> agent_categories + agents.classify
        "skills" line                  -> agent_tags
        source quotes or manifest text -> agents.chat_prologue
        category question templates    -> agent_questions

    Chinese display values stay in persona-library-categories.json because Windows
    PowerShell 5.1 reads BOM-less .ps1 files using the ANSI code page. This script is
    intentionally ASCII-only and reads JSON/Markdown explicitly as UTF-8.

    Generated SQL is idempotent and can be re-run.

.EXAMPLE
    .\tools\generate-persona-library-sql.ps1

.EXAMPLE
    .\tools\generate-persona-library-sql.ps1 `
      -LibraryRoot D:\hub\awesome-ai-persona-skills -Version V13
#>
[CmdletBinding()]
param(
    [string]$LibraryRoot = 'D:\hub\awesome-ai-persona-skills',
    [string]$Version = 'V13',
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot "..\src\main\resources\db\migration\${Version}__seed_persona_library.sql"
}

if (-not (Test-Path -LiteralPath $LibraryRoot)) {
    throw "Persona library directory not found: $LibraryRoot"
}

$manifestPath = Join-Path $PSScriptRoot 'persona-library-categories.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$categories = @($manifest.categories)
$publishedAt = [string]$manifest.publishedAt
if (-not $publishedAt) {
    throw 'Manifest is missing publishedAt.'
}

$favorLabel = [string][char]0x64C5 + [string][char]0x957F
$middleDot = [char]0x00B7
$katakanaMiddleDot = [char]0x30FB
$tagDelimiters = [char[]]@([char]0x3001, [char]0xFF0C, [char]0xFF1B, ',', ';')
$quoteCharacters = [char[]]@('"', [char]0x201C, [char]0x201D)

function ConvertTo-SqlLiteral {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value) { return '' }
    return $Value.Replace('\', '\\').Replace("'", "''")
}

function Limit-Text {
    param(
        [AllowEmptyString()][string]$Value,
        [int]$MaxLength
    )

    if ($null -eq $Value) { return '' }
    $trimmed = $Value.Trim()
    if ($trimmed.Length -le $MaxLength) { return $trimmed }
    return $trimmed.Substring(0, [Math]::Max(0, $MaxLength - 3)) + '...'
}

function Normalize-RelativePath {
    param([string]$Value)

    return $Value.Replace('\', '/').TrimStart('/')
}

function Get-IndentLength {
    param([string]$Line)

    return ($Line -replace '^(\s*).*$', '$1').Length
}

function Read-Definition {
    param(
        [System.IO.FileInfo]$File,
        [object]$Category,
        [string]$RelativePath,
        [int]$SortOrder
    )

    $lines = [System.IO.File]::ReadAllLines(
        $File.FullName,
        (New-Object System.Text.UTF8Encoding($false)))
    if ($lines.Count -eq 0) {
        throw "Empty definition: $($File.FullName)"
    }

    $meta = @{}
    $bodyStart = 0
    if ($lines[0].Trim() -eq '---') {
        $end = -1
        for ($index = 1; $index -lt $lines.Count; $index++) {
            if ($lines[$index].Trim() -eq '---') {
                $end = $index
                break
            }
        }
        if ($end -lt 0) {
            throw "Unterminated YAML front matter: $($File.FullName)"
        }

        $blockKey = $null
        $blockLines = New-Object System.Collections.Generic.List[string]
        $blockIndent = 0

        for ($index = 1; $index -lt $end; $index++) {
            $line = $lines[$index]

            if ($blockKey) {
                $indent = Get-IndentLength $line
                if ($line.Trim().Length -eq 0 -or $indent -gt $blockIndent) {
                    if ($line.Trim().Length -eq 0) {
                        $blockLines.Add('')
                    } else {
                        $cut = [Math]::Min($blockIndent + 2, $line.Length)
                        $blockLines.Add($line.Substring($cut).TrimEnd())
                    }
                    continue
                }

                $meta[$blockKey] = ($blockLines -join "`n").Trim()
                $blockKey = $null
                $blockLines.Clear()
            }

            if ($line -match '^([^:\s][^:]*?)\s*:\s*(.*)$') {
                $key = $Matches[1].Trim()
                $value = $Matches[2].Trim()
                if ($value -eq '|' -or $value -eq '|-' -or $value -eq '>') {
                    $blockKey = $key
                    $blockIndent = Get-IndentLength $line
                } else {
                    $meta[$key] = $value.Trim('"').Trim("'")
                }
            }
        }

        if ($blockKey) {
            $meta[$blockKey] = ($blockLines -join "`n").Trim()
        }
        $bodyStart = $end + 1
    }

    $body = ''
    if ($bodyStart -lt $lines.Count) {
        $body = ($lines[$bodyStart..($lines.Count - 1)] -join "`n").Trim()
    }

    $displayName = ''
    $bodyLines = @()
    if ($body) {
        $bodyLines = @($body -split "`n")
    }

    if ([string]$Category.format -eq 'director') {
        $displayName = [System.IO.Path]::GetFileNameWithoutExtension($File.Name)
    } else {
        foreach ($line in $bodyLines) {
            if ($line -match '^#\s+(.+?)\s*$') {
                $displayName = $Matches[1].Split(
                    [char[]]@($middleDot, $katakanaMiddleDot))[0].Trim()
                break
            }
        }
        if (-not $displayName) {
            $displayName = [System.IO.Path]::GetFileNameWithoutExtension($File.Name)
        }
    }

    if (-not $displayName) {
        throw "Unable to derive display name: $($File.FullName)"
    }

    $agentKey = ''
    if ([string]$Category.format -eq 'director') {
        $keyOverrides = @{}
        if ($Category.PSObject.Properties.Name -contains 'keyOverrides') {
            foreach ($property in $Category.keyOverrides.PSObject.Properties) {
                $keyOverrides[$property.Name] = [string]$property.Value
            }
        }
        if ($keyOverrides.ContainsKey($File.Name)) {
            $agentKey = $keyOverrides[$File.Name]
        } elseif ([string]$Category.keyPrefix) {
            $agentKey = [string]$Category.keyPrefix +
                    ([System.IO.Path]::GetFileNameWithoutExtension($File.Name) -replace '[^\p{L}\p{N}]+', '-').Trim('-')
        }
    } elseif ($meta.ContainsKey('name')) {
        $agentKey = [string]$meta['name']
    }

    if (-not $agentKey) {
        throw "Missing stable agent_key: $($File.FullName)"
    }
    if ($agentKey.Length -gt 64) {
        throw "agent_key exceeds 64 characters: $agentKey"
    }

    $descriptionBlock = ''
    if ($meta.ContainsKey('description')) {
        $descriptionBlock = [string]$meta['description']
    }

    $description = ''
    if ($descriptionBlock) {
        foreach ($line in @($descriptionBlock -split "`n")) {
            if ($line.Trim()) {
                $description = $line.Trim()
                break
            }
        }
    }

    $tags = New-Object System.Collections.Generic.List[string]
    if ([string]$Category.tag) {
        $tags.Add([string]$Category.tag)
    }

    if ([string]$Category.format -eq 'director') {
        $coreHeadings = New-Object System.Collections.Generic.List[string]
        for ($index = 0; $index -lt $bodyLines.Count; $index++) {
            $line = $bodyLines[$index]
            if ($line -match '^###\s+\d+[^\p{L}\p{N}\s]?\s*(.+?)\s*$') {
                $coreHeadings.Add((Limit-Text $Matches[1] 40))
            }
            if ($coreHeadings.Count -ge 5) {
                break
            }
        }
        foreach ($heading in $coreHeadings) {
            $tags.Add($heading)
        }

        $descriptionTemplate = ''
        if ($Category.PSObject.Properties.Name -contains 'descriptionTemplate') {
            $descriptionTemplate = [string]$Category.descriptionTemplate
        }
        $descriptionThemesTemplate = ''
        if ($Category.PSObject.Properties.Name -contains 'descriptionThemesTemplate') {
            $descriptionThemesTemplate = [string]$Category.descriptionThemesTemplate
        }

        if ($coreHeadings.Count -gt 0 -and $descriptionThemesTemplate) {
            $description = $descriptionThemesTemplate.
                    Replace('{name}', $displayName).
                    Replace('{themes}', ($coreHeadings -join '; '))
        } elseif ($descriptionTemplate) {
            $description = $descriptionTemplate.Replace('{name}', $displayName)
        } else {
            $description = "Director style persona for $displayName."
        }
    } else {
        $descriptionLines = @()
        if ($descriptionBlock) {
            $descriptionLines = @($descriptionBlock -split "`n")
        }
        for ($index = 0; $index -lt $descriptionLines.Count; $index++) {
            if ($descriptionLines[$index] -match ("^\s*" + [regex]::Escape($favorLabel) + "\s*[:\uFF1A]\s*(.*)$")) {
                $favorText = (($descriptionLines[$index..($descriptionLines.Count - 1)] |
                        ForEach-Object { $_.Trim() }) -join ' ')
                $favorText = $favorText -replace ("^" + [regex]::Escape($favorLabel) + "\s*[:\uFF1A]\s*"), ''
                foreach ($tag in ($favorText -split ($tagDelimiters -join '|'))) {
                    if ($tag.Trim()) {
                        $tags.Add((Limit-Text $tag 64))
                    }
                }
                break
            }
        }
    }

    $uniqueTags = New-Object System.Collections.Generic.List[string]
    foreach ($tag in $tags) {
        $trimmedTag = $tag.Trim()
        if ($trimmedTag -and -not $uniqueTags.Contains($trimmedTag)) {
            $uniqueTags.Add($trimmedTag)
        }
        if ($uniqueTags.Count -ge 8) {
            break
        }
    }

    $chatPrologue = ''
    for ($index = 0; $index -lt $bodyLines.Count; $index++) {
        if ($bodyLines[$index].TrimStart().StartsWith('>')) {
            $quoteLines = New-Object System.Collections.Generic.List[string]
            for ($quoteIndex = $index; $quoteIndex -lt $bodyLines.Count; $quoteIndex++) {
                if (-not $bodyLines[$quoteIndex].TrimStart().StartsWith('>')) {
                    break
                }
                $quoted = (($bodyLines[$quoteIndex].Trim() -replace '^>\s*', '').Trim($quoteCharacters)).Trim()
                if ($quoted) {
                    $quoteLines.Add($quoted)
                }
                if ($quoteLines.Count -ge 2) {
                    break
                }
            }
            $chatPrologue = ($quoteLines -join "`n")
            break
        }
    }

    if (-not $chatPrologue) {
        $chatPrologue = ([string]$Category.chatPrologueTemplate).Replace('{name}', $displayName)
    }

    $questions = New-Object System.Collections.Generic.List[string]
    foreach ($template in @($Category.questionTemplates)) {
        if ($template) {
            $questions.Add((Limit-Text ([string]$template).Replace('{name}', $displayName) 512))
        }
    }

    $sourceModel = ''
    if ($meta.ContainsKey('model')) {
        $sourceModel = [string]$meta['model']
    }

    [pscustomobject]@{
        Category      = [string]$Category.key
        CategoryName  = [string]$Category.name
        Classify      = [string]$Category.classify
        ChildClassify = [string]$Category.childClassify
        Key           = $agentKey
        Name          = $displayName
        Description   = Limit-Text $description 1024
        Prompt        = $body
        ChatPrologue  = Limit-Text $chatPrologue 1024
        SourceModel   = $sourceModel
        SourcePath    = $RelativePath
        Tags          = $uniqueTags
        Questions     = $questions
        SortOrder     = $SortOrder
    }
}

$definitions = New-Object System.Collections.Generic.List[object]
$categoryRows = New-Object System.Collections.Generic.List[object]

foreach ($category in $categories) {
    $directory = Join-Path $LibraryRoot ([string]$category.folder).Replace('/', '\')
    if (-not (Test-Path -LiteralPath $directory)) {
        throw "Category directory not found: $directory"
    }

    if ($category.recursive) {
        $files = @(Get-ChildItem -LiteralPath $directory -Recurse -File -Filter ([string]$category.pattern))
    } else {
        $files = @(Get-ChildItem -LiteralPath $directory -File -Filter ([string]$category.pattern))
    }

    $excluded = @{}
    if ($category.PSObject.Properties.Name -contains 'excludePaths') {
        foreach ($excludePath in @($category.excludePaths)) {
            $excluded[(Normalize-RelativePath ([string]$excludePath)).ToLowerInvariant()] = $true
        }
    }

    $included = New-Object System.Collections.Generic.List[object]
    foreach ($file in ($files | Sort-Object FullName)) {
        $relativePath = Normalize-RelativePath $file.FullName.Substring($LibraryRoot.Length).TrimStart('\', '/')
        if ($excluded.ContainsKey($relativePath.ToLowerInvariant())) {
            continue
        }
        $included.Add([pscustomobject]@{
            File = $file
            RelativePath = $relativePath
        })
    }

    $categoryRows.Add([pscustomobject]@{
        Key         = [string]$category.key
        Name        = [string]$category.name
        Description = [string]$category.description
        Sort        = [int]$category.sort
        Count       = $included.Count
    })

    $index = 0
    foreach ($item in $included) {
        $index++
        $definition = Read-Definition `
                -File $item.File `
                -Category $category `
                -RelativePath $item.RelativePath `
                -SortOrder ($index * 10)
        $definitions.Add($definition)
    }
}

$duplicates = @($definitions | Group-Object Key | Where-Object { $_.Count -gt 1 })
if ($duplicates.Count -gt 0) {
    throw "Duplicate agent_key: $(($duplicates | ForEach-Object { $_.Name }) -join ', ')"
}

$out = New-Object System.Text.StringBuilder
$generatedOn = (Get-Date).ToString('yyyy-MM-dd')

[void]$out.AppendLine("-- ${Version}: seed the awesome-ai-persona-skills persona library.")
[void]$out.AppendLine('--')
[void]$out.AppendLine("-- Source: $($manifest.source)")
[void]$out.AppendLine("-- Generated by tools/generate-persona-library-sql.ps1 on ${generatedOn}.")
[void]$out.AppendLine('-- Do not hand-edit this file; change the library or the generator and regenerate.')
[void]$out.AppendLine('--')
[void]$out.AppendLine("-- $($definitions.Count) agents in $($categoryRows.Count) categories:")
foreach ($row in $categoryRows) {
    [void]$out.AppendLine("--   $($row.Key.PadRight(20)) $($row.Name) ($($row.Count))")
}
[void]$out.AppendLine('--')
[void]$out.AppendLine('-- Front matter summary blocks are flattened into agents.description. The skills line and')
[void]$out.AppendLine('-- director core headings become tags. Existing quotes or category templates become')
[void]$out.AppendLine('-- chat_prologue, and category templates become suggested questions.')
[void]$out.AppendLine('')
[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('-- Categories')
[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('')

foreach ($row in $categoryRows) {
    [void]$out.AppendLine('INSERT INTO agent_categories')
    [void]$out.AppendLine('    (site_id, category_key, name, description, sort_order, status)')
    [void]$out.AppendLine("SELECT s.id, '$(ConvertTo-SqlLiteral $row.Key)', '$(ConvertTo-SqlLiteral $row.Name)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $row.Description)', $($row.Sort), 'ACTIVE'")
    [void]$out.AppendLine('FROM app_sites s')
    [void]$out.AppendLine("WHERE s.site_key = 'default'")
    [void]$out.AppendLine('ON DUPLICATE KEY UPDATE')
    [void]$out.AppendLine('    name = VALUES(name),')
    [void]$out.AppendLine('    description = VALUES(description),')
    [void]$out.AppendLine('    sort_order = VALUES(sort_order),')
    [void]$out.AppendLine('    status = VALUES(status);')
    [void]$out.AppendLine('')
}

[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('-- Rebuild generated relations')
[void]$out.AppendLine('-- ---------------------------------------------------------------------------')
[void]$out.AppendLine('')
$keyList = ($definitions | ForEach-Object { "'$(ConvertTo-SqlLiteral $_.Key)'" }) -join ', '
[void]$out.AppendLine('DELETE FROM agent_tags')
[void]$out.AppendLine('WHERE agent_id IN (')
[void]$out.AppendLine('    SELECT id FROM agents')
[void]$out.AppendLine("    WHERE site_id = (SELECT id FROM app_sites WHERE site_key = 'default')")
[void]$out.AppendLine("      AND agent_key IN ($keyList));")
[void]$out.AppendLine('')
[void]$out.AppendLine('DELETE FROM agent_questions')
[void]$out.AppendLine('WHERE agent_id IN (')
[void]$out.AppendLine('    SELECT id FROM agents')
[void]$out.AppendLine("    WHERE site_id = (SELECT id FROM app_sites WHERE site_key = 'default')")
[void]$out.AppendLine("      AND agent_key IN ($keyList));")
[void]$out.AppendLine('')

$currentCategory = $null
foreach ($definition in $definitions) {
    if ($definition.Category -ne $currentCategory) {
        $currentCategory = $definition.Category
        [void]$out.AppendLine('-- ---------------------------------------------------------------------------')
        [void]$out.AppendLine("-- $($definition.CategoryName)")
        [void]$out.AppendLine('-- ---------------------------------------------------------------------------')
        [void]$out.AppendLine('')
    }

    [void]$out.AppendLine("-- $($definition.SourcePath)")
    [void]$out.AppendLine('INSERT INTO agents (site_id, category_id, agent_key, sn, agent_type, ui_type, scope,')
    [void]$out.AppendLine('                    classify, child_classify, chat_suggest, name, description,')
    [void]$out.AppendLine('                    system_prompt, chat_prologue, source_model, source_path, status,')
    [void]$out.AppendLine('                    sort_order, visibility, publish_status, published_at, score)')
    [void]$out.AppendLine('SELECT s.id, c.id,')
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Key)', '$(ConvertTo-SqlLiteral $definition.Key)',")
    [void]$out.AppendLine("       'OFFICIAL', 1, 1, '$(ConvertTo-SqlLiteral $definition.Classify)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.ChildClassify)', 1,")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Name)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Description)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.Prompt)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.ChatPrologue)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.SourceModel)',")
    [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $definition.SourcePath)',")
    [void]$out.AppendLine("       'ACTIVE', $($definition.SortOrder), 'PUBLIC', 'PUBLISHED',")
    [void]$out.AppendLine("       '${publishedAt}', 5.00")
    [void]$out.AppendLine('FROM app_sites s')
    [void]$out.AppendLine("JOIN agent_categories c ON c.site_id = s.id AND c.category_key = '$(ConvertTo-SqlLiteral $definition.Category)'")
    [void]$out.AppendLine("WHERE s.site_key = 'default'")
    [void]$out.AppendLine('ON DUPLICATE KEY UPDATE')
    [void]$out.AppendLine('    category_id = VALUES(category_id),')
    [void]$out.AppendLine('    sn = VALUES(sn),')
    [void]$out.AppendLine('    agent_type = VALUES(agent_type),')
    [void]$out.AppendLine('    ui_type = VALUES(ui_type),')
    [void]$out.AppendLine('    scope = VALUES(scope),')
    [void]$out.AppendLine('    classify = VALUES(classify),')
    [void]$out.AppendLine('    child_classify = VALUES(child_classify),')
    [void]$out.AppendLine('    chat_suggest = VALUES(chat_suggest),')
    [void]$out.AppendLine('    name = VALUES(name),')
    [void]$out.AppendLine('    description = VALUES(description),')
    [void]$out.AppendLine('    system_prompt = VALUES(system_prompt),')
    [void]$out.AppendLine('    chat_prologue = VALUES(chat_prologue),')
    [void]$out.AppendLine('    source_model = VALUES(source_model),')
    [void]$out.AppendLine('    source_path = VALUES(source_path),')
    [void]$out.AppendLine('    status = VALUES(status),')
    [void]$out.AppendLine('    sort_order = VALUES(sort_order),')
    [void]$out.AppendLine('    visibility = VALUES(visibility),')
    [void]$out.AppendLine('    publish_status = VALUES(publish_status),')
    [void]$out.AppendLine('    published_at = VALUES(published_at),')
    [void]$out.AppendLine('    score = VALUES(score);')
    [void]$out.AppendLine('')

    $tagIndex = 0
    foreach ($tag in $definition.Tags) {
        [void]$out.AppendLine('INSERT INTO agent_tags (agent_id, tag, sort_order)')
        [void]$out.AppendLine('SELECT a.id,')
        [void]$out.AppendLine("       '$(ConvertTo-SqlLiteral $tag)', $tagIndex")
        [void]$out.AppendLine('FROM agents a')
        [void]$out.AppendLine('JOIN app_sites s ON s.id = a.site_id')
        [void]$out.AppendLine("WHERE s.site_key = 'default' AND a.agent_key = '$(ConvertTo-SqlLiteral $definition.Key)'")
        [void]$out.AppendLine('ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order);')
        [void]$out.AppendLine('')
        $tagIndex++
    }

    $questionIndex = 0
    foreach ($question in $definition.Questions) {
        [void]$out.AppendLine('INSERT INTO agent_questions (agent_id, question_type, content, sort_order)')
        [void]$out.AppendLine('SELECT a.id,')
        [void]$out.AppendLine("       'SUGGEST', '$(ConvertTo-SqlLiteral $question)', $questionIndex")
        [void]$out.AppendLine('FROM agents a')
        [void]$out.AppendLine('JOIN app_sites s ON s.id = a.site_id')
        [void]$out.AppendLine("WHERE s.site_key = 'default' AND a.agent_key = '$(ConvertTo-SqlLiteral $definition.Key)';")
        [void]$out.AppendLine('')
        $questionIndex++
    }
}

$resolved = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText(
    $resolved,
    $out.ToString(),
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Wrote $resolved"
Write-Host "Agents: $($definitions.Count); categories: $($categoryRows.Count)"
