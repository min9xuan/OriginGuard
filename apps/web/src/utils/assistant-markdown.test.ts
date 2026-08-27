import { describe, expect, it } from 'vitest'
import { renderAssistantMarkdown } from './assistant-markdown'

describe('renderAssistantMarkdown', () => {
  it('renders headings, emphasis, separators and lists', () => {
    const html = renderAssistantMarkdown(`**初步判断：很可能由 AI 生成**

---

### 判断依据
- **模型概率**：96.45%
1. 需要人工核验`)

    expect(html).toContain('<strong>初步判断：很可能由 AI 生成</strong>')
    expect(html).toContain('<hr />')
    expect(html).toContain('<h3>判断依据</h3>')
    expect(html).toContain('<ul><li><strong>模型概率</strong>：96.45%</li></ul>')
    expect(html).toContain('<ol><li>需要人工核验</li></ol>')
  })

  it('escapes model supplied html before adding formatting', () => {
    const html = renderAssistantMarkdown('<img src=x onerror=alert(1)> **安全**')

    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
    expect(html).toContain('<strong>安全</strong>')
  })
})
