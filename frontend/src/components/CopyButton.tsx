import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckIcon, CopyIcon } from './icons'

export function CopyButton({ text }: { text: string }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)

  function copy() {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <button
      className={`copy-btn${copied ? ' copied' : ''}`}
      onClick={copy}
      type="button"
      aria-label={copied ? t('common.actions.copied') : t('common.actions.copy')}
      title={copied ? t('common.actions.copied') : t('common.actions.copy')}
    >
      {copied ? <CheckIcon width={13} height={13} /> : <CopyIcon width={13} height={13} />}
    </button>
  )
}
