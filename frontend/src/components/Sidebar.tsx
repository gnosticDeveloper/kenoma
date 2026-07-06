import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { useTheme } from '../hooks/useTheme'
import { useSidebarCollapsed } from '../hooks/useSidebarCollapsed'
import {
  CollapseIcon, ExpandIcon, LanguageIcon, LogoutIcon,
  ThemeAutoIcon, ThemeDarkIcon, ThemeLightIcon,
} from './icons'

export interface NavItem {
  id: string
  labelKey: string
  icon: ComponentType<{ width?: number; height?: number }>
}

export interface NavGroup {
  labelKey: string
  items: NavItem[]
}

interface Props {
  groups: NavGroup[]
  activeId: string
  onSelect: (id: string) => void
  onLogout: () => void
}

const THEME_ICONS = { auto: ThemeAutoIcon, dark: ThemeDarkIcon, light: ThemeLightIcon }

export function Sidebar({ groups, activeId, onSelect, onLogout }: Props) {
  const { t, i18n } = useTranslation()
  const { mode, cycle } = useTheme()
  const { collapsed, toggle } = useSidebarCollapsed()
  const ThemeIcon = THEME_ICONS[mode]

  function toggleLanguage() {
    i18n.changeLanguage(i18n.language === 'es' ? 'en' : 'es')
  }

  return (
    <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
      <div className="sidebar-logo">
        <div className="sidebar-mark">K</div>
        <span className="sidebar-title">Kenoma</span>
        <button className="sidebar-collapse-btn" onClick={toggle} aria-label="Toggle sidebar" type="button">
          {collapsed ? <ExpandIcon /> : <CollapseIcon />}
        </button>
      </div>
      <nav className="sidebar-nav">
        {groups.length === 0 ? (
          <div className="sidebar-empty">{t('nav.noPermissions')}</div>
        ) : (
          groups.map(group => (
            <div key={group.labelKey} className="sidebar-group">
              <div className="sidebar-group-label">{t(group.labelKey)}</div>
              {group.items.map(item => (
                <button
                  key={item.id}
                  className={`sidebar-item${activeId === item.id ? ' active' : ''}`}
                  onClick={() => onSelect(item.id)}
                  type="button"
                >
                  <item.icon width={18} height={18} />
                  <span className="sidebar-item-label">{t(item.labelKey)}</span>
                </button>
              ))}
            </div>
          ))
        )}
      </nav>
      <div className="sidebar-footer">
        <button className="sidebar-tool-btn" onClick={cycle} type="button" title={t(`common.theme.${mode}`)}>
          <ThemeIcon width={16} height={16} />
          <span className="sidebar-tool-label">{t(`common.theme.${mode}`)}</span>
        </button>
        <button className="sidebar-tool-btn" onClick={toggleLanguage} type="button" title={i18n.language.toUpperCase()}>
          <LanguageIcon width={16} height={16} />
          <span className="sidebar-tool-label">{i18n.language === 'es' ? 'Español' : 'English'}</span>
        </button>
        <button className="sidebar-tool-btn" onClick={onLogout} type="button">
          <LogoutIcon width={16} height={16} />
          <span className="sidebar-tool-label">{t('nav.logout')}</span>
        </button>
      </div>
    </aside>
  )
}
