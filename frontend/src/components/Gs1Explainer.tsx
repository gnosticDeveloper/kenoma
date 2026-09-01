import { useTranslation } from 'react-i18next'

/** Bar widths (in px) for a decorative barcode strip. Not a real encoding. */
const RETAIL_BARS = [2, 1, 3, 1, 1, 2, 1, 4, 1, 2, 1, 1, 3, 1, 2, 2, 1, 3, 1, 1]
const GS1_BARS = [
  1, 2, 1, 1, 3, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 3, 1, 1, 2, 1,
  2, 1, 3, 1, 1, 2, 1, 1, 3, 2, 1, 1, 2, 1, 4, 1, 2, 1, 1, 2,
]

function Barcode({ bars, height }: { bars: number[]; height: number }) {
  let x = 0
  return (
    <>
      {bars.map((w, i) => {
        const rect = i % 2 === 0
          ? <rect key={i} x={x} y={0} width={w} height={height} fill="currentColor" />
          : null
        x += w
        return rect
      })}
    </>
  )
}

/** Help content for the GS1-128 scan field: a short line, a labelled
  * retail-unit vs case/carton illustration, and a paragraph of context.
  * Rendered inside an InfoTip, not inline in the form. */
export function Gs1Help() {
  const { t } = useTranslation()
  return (
    <div className="gs1-help">
      <p className="gs1-help-lead">{t('bimeStockPage.batchGs1Hint')}</p>
      <div className="gs1-compare">
        <figure>
          <svg viewBox="0 0 44 34" role="img" aria-hidden="true">
            <g transform="translate(2,4)">
              <Barcode bars={RETAIL_BARS} height={22} />
            </g>
          </svg>
          <figcaption>
            <strong>{t('bimeStockPage.batchGs1RetailCaption')}</strong>
            <span>{t('bimeStockPage.batchGs1RetailSub')}</span>
          </figcaption>
        </figure>
        <figure>
          <svg viewBox="0 0 92 34" role="img" aria-hidden="true">
            <g transform="translate(2,4)">
              <Barcode bars={GS1_BARS} height={22} />
            </g>
          </svg>
          <figcaption>
            <strong>{t('bimeStockPage.batchGs1CaseCaption')}</strong>
            <span>{t('bimeStockPage.batchGs1CaseSub')}</span>
          </figcaption>
        </figure>
      </div>
      <p className="gs1-help-body">{t('bimeStockPage.batchGs1ExplainBody')}</p>
    </div>
  )
}
