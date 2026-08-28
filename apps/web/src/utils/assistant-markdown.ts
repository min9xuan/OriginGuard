export function renderAssistantMarkdown(markdown: string): string {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n')
  const output: string[] = []
  const paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null

  const closeParagraph = () => {
    if (!paragraph.length) return
    output.push(`<p>${paragraph.map(renderInline).join('<br />')}</p>`)
    paragraph.length = 0
  }
  const closeList = () => {
    if (!listType) return
    output.push(`</${listType}>`)
    listType = null
  }
  const openList = (type: 'ul' | 'ol') => {
    closeParagraph()
    if (listType === type) return
    closeList()
    listType = type
    output.push(`<${type}>`)
  }

  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) {
      closeParagraph()
      closeList()
      continue
    }
    if (/^(?:-{3,}|\*{3,}|_{3,})$/.test(line)) {
      closeParagraph()
      closeList()
      output.push('<hr />')
      continue
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(line)
    if (heading) {
      closeParagraph()
      closeList()
      const level = Math.max(2, Math.min(heading[1].length, 4))
      output.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      continue
    }
    const unordered = /^[-*+]\s+(.+)$/.exec(line)
    if (unordered) {
      openList('ul')
      output.push(`<li>${renderInline(unordered[1])}</li>`)
      continue
    }
    const ordered = /^\d+[.)]\s+(.+)$/.exec(line)
    if (ordered) {
      openList('ol')
      output.push(`<li>${renderInline(ordered[1])}</li>`)
      continue
    }
    closeList()
    paragraph.push(line)
  }

  closeParagraph()
  closeList()
  return output.join('')
}

function renderInline(value: string): string {
  return replaceAssistantIcons(escapeHtml(value))
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, '<em>$1</em>')
}

type AssistantIconName = 'alert' | 'check' | 'note' | 'search' | 'document' | 'agent' | 'experiment'

const assistantIconPatterns: Array<[RegExp, AssistantIconName]> = [
  [/(?:🛑|⛔|❗|‼️?|🔴|🚫)/gu, 'alert'],
  [/(?:✅|☑️?|✔️?|🟢)/gu, 'check'],
  [/(?:📌|📍|💡|ℹ️?)/gu, 'note'],
  [/(?:🔎|🔍)/gu, 'search'],
  [/(?:📋|🧾|📄|📑)/gu, 'document'],
  [/(?:🤖)/gu, 'agent'],
  [/(?:🧪|🔬)/gu, 'experiment'],
]

function replaceAssistantIcons(value: string): string {
  return assistantIconPatterns.reduce(
    (result, [pattern, name]) => result.replace(pattern, assistantIcon(name)),
    value,
  )
}

function assistantIcon(name: AssistantIconName): string {
  const paths: Record<AssistantIconName, string> = {
    alert: '<path d="M8 2.2 14 13H2L8 2.2Z"/><path d="M8 5.7v3.5"/><path d="M8 11.4h.01"/>',
    check: '<circle cx="8" cy="8" r="5.8"/><path d="m5.2 8.1 1.8 1.8 3.8-4"/>',
    note: '<path d="M5 2.5h6v4.1l1.7 1.7H9v5.2L7.3 12V8.3h-4l1.7-1.7V2.5Z"/>',
    search: '<circle cx="7" cy="7" r="4.3"/><path d="m10.2 10.2 3.2 3.2"/>',
    document: '<path d="M4 2.2h5l3 3v8.6H4V2.2Z"/><path d="M9 2.2v3h3M6.2 8h3.6M6.2 10.5h3.6"/>',
    agent: '<rect x="3" y="4.2" width="10" height="8.3" rx="2"/><path d="M8 2v2.2M5.7 7.5h.01M10.3 7.5h.01M6 10h4"/>',
    experiment: '<path d="M6 2.2h4M7 2.2v3.2l-3.5 6.1A1.5 1.5 0 0 0 4.8 14h6.4a1.5 1.5 0 0 0 1.3-2.5L9 5.4V2.2M5.3 10h5.4"/>',
  }
  return `<span class="assistant-inline-icon ${name}" aria-hidden="true"><svg viewBox="0 0 16 16">${paths[name]}</svg></span>`
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
