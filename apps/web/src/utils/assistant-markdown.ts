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
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, '<em>$1</em>')
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
