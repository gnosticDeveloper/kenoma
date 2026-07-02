import { useState } from 'react'

export function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  function copy() {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <button className={`copy-btn${copied ? ' copied' : ''}`} onClick={copy}>
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}
