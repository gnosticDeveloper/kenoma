import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import es from './locales/es.json'

const LANG_KEY = 'kenoma.lang'

function initialLanguage(): string {
  const stored = localStorage.getItem(LANG_KEY)
  return stored === 'es' || stored === 'en' ? stored : 'en'
}

i18next.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    es: { translation: es },
  },
  lng: initialLanguage(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

i18next.on('languageChanged', lang => localStorage.setItem(LANG_KEY, lang))

export default i18next
