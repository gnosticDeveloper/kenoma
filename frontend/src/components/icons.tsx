import type { SVGProps } from 'react'

function Icon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg
      viewBox="0 0 20 20"
      width="18"
      height="18"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    />
  )
}

export function OrgsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <rect x="3" y="3" width="6" height="6" rx="1" />
      <rect x="11" y="3" width="6" height="6" rx="1" />
      <rect x="3" y="11" width="6" height="6" rx="1" />
      <rect x="11" y="11" width="6" height="6" rx="1" />
    </Icon>
  )
}

export function ExportsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M10 2v9" />
      <path d="M6.5 7.5 10 11l3.5-3.5" />
      <path d="M3 13v2a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-2" />
    </Icon>
  )
}

export function PricingIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M10 2v16M13.5 5.5c0-1.4-1.6-2.5-3.5-2.5S6.5 4.1 6.5 5.5s1.6 2.2 3.5 2.7c1.9.5 3.5 1.3 3.5 2.7S12 13.5 10 13.5s-3.5-1.1-3.5-2.5" />
    </Icon>
  )
}

export function ServicesIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="10" cy="10" r="3" />
      <path d="M10 3v2M10 15v2M3 10h2M15 10h2M5.3 5.3l1.4 1.4M13.3 13.3l1.4 1.4M14.7 5.3l-1.4 1.4M6.7 13.3l-1.4 1.4" />
    </Icon>
  )
}

export function CredentialsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="7" cy="10" r="3.2" />
      <path d="M9.8 8 16 8M13 8v3M16 8v3" />
    </Icon>
  )
}

export function OnboardingIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M4 4h8l3 3v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z" />
      <path d="M12 4v3h3" />
      <path d="M7 12h6M7 15h4" />
    </Icon>
  )
}

export function UsersIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="7.5" cy="7" r="2.7" />
      <path d="M2.5 16c0-2.6 2.2-4 5-4s5 1.4 5 4" />
      <circle cx="14.5" cy="7.5" r="2" />
      <path d="M13 12.3c2 .2 3.5 1.4 3.5 3.7" />
    </Icon>
  )
}

export function LocationsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M10 17s5.5-4.7 5.5-9A5.5 5.5 0 0 0 4.5 8c0 4.3 5.5 9 5.5 9Z" />
      <circle cx="10" cy="8" r="1.8" />
    </Icon>
  )
}

export function MetadataIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M3 6.5 10 3l7 3.5-7 3.5-7-3.5Z" />
      <path d="M3 10.3 10 13.8l7-3.5M3 13.8 10 17.3l7-3.5" />
    </Icon>
  )
}

export function ProductsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M3 6 10 3l7 3v8l-7 3-7-3Z" />
      <path d="M3 6l7 3 7-3M10 9v8" />
    </Icon>
  )
}

export function StockIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <rect x="3" y="8" width="14" height="8" rx="1" />
      <path d="M3 8l7-5 7 5M8 12h4" />
    </Icon>
  )
}

export function DrBackupsIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M10 2 3.5 4.5v5c0 4 2.8 6.7 6.5 8.5 3.7-1.8 6.5-4.5 6.5-8.5v-5L10 2Z" />
      <path d="M7 10.2l2.1 2.1L13.3 8" />
    </Icon>
  )
}

export function LogoMarkIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props} strokeWidth="1.8">
      <circle cx="7" cy="10" r="6" />
      <circle cx="13" cy="10" r="6" />
    </Icon>
  )
}

export function CollapseIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M12.5 4 7 10l5.5 6" />
    </Icon>
  )
}

export function ExpandIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M7.5 4 13 10l-5.5 6" />
    </Icon>
  )
}

export function LanguageIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="10" cy="10" r="7" />
      <path d="M3 10h14M10 3c2 2.2 3 5 3 7s-1 4.8-3 7c-2-2.2-3-5-3-7s1-4.8 3-7Z" />
    </Icon>
  )
}

export function LogoutIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M8 4H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" />
      <path d="M13 14l4-4-4-4M17 10H7.5" />
    </Icon>
  )
}

export function SearchIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="8.5" cy="8.5" r="5" />
      <path d="M16 16l-3.8-3.8" />
    </Icon>
  )
}

export function MoreIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <circle cx="10" cy="4.5" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="10" cy="10" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="10" cy="15.5" r="1.1" fill="currentColor" stroke="none" />
    </Icon>
  )
}

export function CloseIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M5 5l10 10M15 5 5 15" />
    </Icon>
  )
}

export function ChevronDownIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M5 8l5 5 5-5" />
    </Icon>
  )
}

export function CheckIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <path d="M4 10.5 8 14.5 16 5.5" />
    </Icon>
  )
}

export function CopyIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Icon {...props}>
      <rect x="7" y="7" width="9" height="9" rx="1.2" />
      <path d="M13 7V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h2" />
    </Icon>
  )
}
