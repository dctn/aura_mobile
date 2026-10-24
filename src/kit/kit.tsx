// The phone's building blocks, ported from demo/src/aura_mobile/ui/kit.py. Screens compose these; colours come from
// tokens.css, and a feature's accent is passed as a token name ("sleep") and set as --tone.

import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react';
import { ICONS, tone } from '../lib/catalog';
import { useNav, type Page as PageSpec } from '../app/nav';

// -- icons ----------------------------------------------------------------------------------------------------------

export function Icon({ name, size = 20, stroke = 1.8, color, className }: { name: string; size?: number; stroke?: number; color?: string; className?: string }) {
  // Only the bundled path strings from catalog.json (ui/icons.py) are ever inserted, never data from the network.
  const body = ICONS[name] ?? ICONS.circle;
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color ?? 'currentColor'}
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: body }}
    />
  );
}

const toneStyle = (accent?: string, extra?: CSSProperties): CSSProperties => ({ ...extra, ['--tone' as string]: tone(accent) });

/** A round icon: white on the accent, or the accent on a soft fill with `soft`. */
export function Disc({ icon, accent, size = 36, soft = false }: { icon: string; accent?: string; size?: number; soft?: boolean }) {
  return (
    <span className={`disc${soft ? ' soft' : ''}`} style={toneStyle(accent, { width: size, height: size })}>
      <Icon name={icon} size={Math.round(size * 0.56)} stroke={2} />
    </span>
  );
}

// -- page frame -----------------------------------------------------------------------------------------------------

export interface PageProps {
  title?: string;
  sub?: ReactNode;
  /** "‹ <label>" at the top left; pressing it pops the page. */
  back?: string;
  onBack?: () => void;
  /** Round buttons at the top right. */
  right?: ReactNode;
  root?: boolean;
  children?: ReactNode;
  /** Gets the scroll container (the calendar scrolls its hours into view). */
  scrollRef?: React.RefObject<HTMLDivElement>;
}

/** One screen: the nav bar, the large title, then the content, scrolling under a floating tab bar. */
export function Page({ title, sub, back, onBack, right, root = false, children, scrollRef }: PageProps) {
  const nav = useNav();
  const own = useRef<HTMLDivElement>(null);
  const ref = scrollRef ?? own;
  useEffect(() => {
    ref.current?.scrollTo({ top: 0 });
  }, [ref]);
  return (
    <div className={`page${root ? ' root' : ''}`} ref={ref}>
      <div className="navbar">
        {back !== undefined && (
          <button className="navback" onClick={onBack ?? nav.back}>
            <Icon name="back" size={22} stroke={2.2} />
            {back}
          </button>
        )}
        <span className="spacer" />
        {right}
      </div>
      <div className="page-body">
        {title && (
          <div>
            <h1 className="largetitle">{title}</h1>
            {sub && <div className="subtitle">{sub}</div>}
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

export function RoundButton({ icon, label, onClick, accent }: { icon: string; label: string; onClick: () => void; accent?: string }) {
  return (
    <button className="round" onClick={onClick} aria-label={label} title={label} style={accent ? { color: tone(accent) } : undefined}>
      <Icon name={icon} size={20} />
    </button>
  );
}

/** The gear on a tab's top right: opens Settings. */
export function SettingsButton() {
  const nav = useNav();
  return <RoundButton icon="settings" label="Settings" onClick={() => nav.push({ name: 'settings' } satisfies PageSpec)} />;
}

export function Section({ title, trailing }: { title: string; trailing?: ReactNode }) {
  return (
    <div className="section">
      <span>{title}</span>
      {trailing}
    </div>
  );
}

// -- lists ----------------------------------------------------------------------------------------------------------

export function Group({ heading, footer, children }: { heading?: string; footer?: ReactNode; children: ReactNode }) {
  return (
    <div>
      {heading && <div className="head">{heading}</div>}
      <div className="list">{children}</div>
      {footer && <div className="foot">{footer}</div>}
    </div>
  );
}

export interface RowProps {
  title: ReactNode;
  sub?: ReactNode;
  subTone?: 'good' | 'warn' | 'bad';
  value?: ReactNode;
  /** A coloured glyph on the left (Search's categories). */
  icon?: string;
  accent?: string;
  /** A soft disc instead of a bare glyph. */
  disc?: boolean;
  chevron?: boolean;
  trailing?: ReactNode;
  look?: 'link' | 'danger' | 'strong';
  onClick?: () => void;
}

export function Row({ title, sub, subTone, value, icon, accent, disc, chevron, trailing, look, onClick }: RowProps) {
  const content = (
    <>
      {icon && (disc ? <Disc icon={icon} accent={accent} size={29} soft /> : <span style={{ color: tone(accent), display: 'flex' }}><Icon name={icon} size={24} stroke={2} /></span>)}
      <span className="grow">
        <div className={`title${look ? ` ${look}` : ''}`}>{title}</div>
        {sub && <div className={`sub${subTone ? ` ${subTone}` : ''}`}>{sub}</div>}
      </span>
      {value !== undefined && <span className="value">{value}</span>}
      {trailing}
      {chevron && <Icon className="chev" name="chevron" size={16} stroke={2} />}
    </>
  );
  const cls = `row${icon ? ' with-icon' : ''}`;
  return onClick ? <button className={cls} onClick={onClick}>{content}</button> : <div className={cls}>{content}</div>;
}

// -- controls -------------------------------------------------------------------------------------------------------

export function Switch({ on, onChange, label }: { on: boolean; onChange: (on: boolean) => void; label?: string }) {
  return <button className={`switch${on ? ' on' : ''}`} role="switch" aria-checked={on} aria-label={label} onClick={() => onChange(!on)} />;
}

export function Segmented<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="segbar" role="tablist">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? 'on' : ''} role="tab" aria-selected={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Stepper({ value, min, max, step = 1, format = (v) => String(v), onChange }: { value: number; min: number; max: number; step?: number; format?: (v: number) => string; onChange: (v: number) => void }) {
  return (
    <span className="stepper">
      <button aria-label="Less" disabled={value <= min} onClick={() => onChange(Math.max(min, value - step))}><Icon name="minus" size={16} stroke={2.2} /></button>
      <span className="val">{format(value)}</span>
      <button aria-label="More" disabled={value >= max} onClick={() => onChange(Math.min(max, value + step))}><Icon name="plus" size={16} stroke={2.2} /></button>
    </span>
  );
}

export function Chips<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T[]; onChange: (v: T[]) => void }) {
  return (
    <div className="chips">
      {options.map((o) => {
        const on = value.includes(o.value);
        return (
          <button key={o.value} className={`chip${on ? ' on' : ''}`} onClick={() => onChange(on ? value.filter((v) => v !== o.value) : [...value, o.value])}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

// -- the Figma look -------------------------------------------------------------------------------------------------

export interface ActionPillProps {
  icon: string;
  title: string;
  reason?: string;
  /** The green one-word button; left out when empty. */
  verb?: string;
  accent?: string;
  onVerb?: () => void;
  /** A tap anywhere else on the pill (the ⓘ sheet). */
  onOpen?: () => void;
  /** Shows the small check (Done). */
  onDone?: () => void;
}

/** The black alert pill: a round icon, the title over a short reason, a check for Done, and one green verb. */
export function ActionPill({ icon, title, reason, verb, accent, onVerb, onOpen, onDone }: ActionPillProps) {
  return (
    <div className="pill" role="button" tabIndex={0} onClick={onOpen} onKeyDown={(e) => e.key === 'Enter' && onOpen?.()}>
      <Disc icon={icon} accent={accent} size={40} />
      <span className="grow">
        <div className="ptitle">{title}</div>
        {reason && <div className="preason">{reason}</div>}
      </span>
      {onDone && (
        <button className="check" aria-label="Done" title="Done" onClick={(e) => { e.stopPropagation(); onDone(); }}>
          <Icon name="check" size={20} stroke={2} />
        </button>
      )}
      {verb && (
        <button className="btn go" onClick={(e) => { e.stopPropagation(); onVerb?.(); }}>{verb}</button>
      )}
    </div>
  );
}

/** Rounded full-height tracks filled from the bottom (the Figma bar look). `values` oldest first; null is empty. */
export function Bars({ values, accent, height = 64 }: { values: (number | null)[]; accent?: string; height?: number }) {
  const top = Math.max(1e-9, ...values.map((v) => (v === null ? 0 : Math.max(0, v))));
  return (
    <div className="bars" style={toneStyle(accent, { height })} aria-hidden="true">
      {values.map((v, i) => {
        const share = v === null || v <= 0 ? 0 : Math.max(v / top, 0.12);
        return (
          <span className="slot" key={i}>
            <span className="track" style={{ height }}>
              {share > 0 && <span className="fill" style={{ height: `${share * 100}%` }} />}
            </span>
          </span>
        );
      })}
    </div>
  );
}

export interface MetricCardProps {
  label: string;
  value: string;
  unit?: string;
  when?: string;
  values: (number | null)[];
  accent?: string;
  chartHeight?: number;
  onClick?: () => void;
}

/** The light metric card: label and "14 days", a big number and unit, then a row of bar tracks. */
export function MetricCard({ label, value, unit, when = '14 days', values, accent, chartHeight = 64, onClick }: MetricCardProps) {
  const body = (
    <>
      <div className="mhead">
        <span className="mlabel">{label}</span>
        <span className="mwhen">{when}</span>
      </div>
      <div className="bignum">
        <span className="n">{value}</span>
        {unit && <span className="u">{unit}</span>}
      </div>
      <Bars values={values} accent={accent} height={chartHeight} />
    </>
  );
  return onClick ? <button className="card mcard" onClick={onClick}>{body}</button> : <div className="card mcard">{body}</div>;
}

/** A two-column grid of tiles; an odd last tile takes the whole row. */
export function TileGrid({ children }: { children: ReactNode[] }) {
  return (
    <div className="tilegrid">
      {children.map((child, i) => (
        <div key={i} className={i === children.length - 1 && i % 2 === 0 ? 'wide' : ''} style={{ display: 'grid' }}>
          {child}
        </div>
      ))}
    </div>
  );
}

export function IconTile({ title, note, icon, accent, onClick }: { title: string; note?: string; icon: string; accent?: string; onClick?: () => void }) {
  return (
    <button className="tile" onClick={onClick}>
      <Disc icon={icon} accent={accent} size={40} soft />
      <span className="tname">{title}</span>
      {note && <span className="tsub">{note}</span>}
    </button>
  );
}

/** The big black "Start walking" pill with a green chevron disc. */
export function StartPill({ text, onClick }: { text: string; onClick: () => void }) {
  return (
    <button className="startpill" onClick={onClick}>
      <span className="disc" style={{ width: 44, height: 44 }}><Icon name="chevron" size={22} stroke={2.6} /></span>
      {text}
    </button>
  );
}

/** A progress ring: `value` 0..1. */
export function Ring({ value, size = 92, stroke = 12, accent = 'accent', children }: { value: number; size?: number; stroke?: number; accent?: string; children?: ReactNode }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const share = Math.min(1, Math.max(0, value));
  return (
    <span style={{ position: 'relative', width: size, height: size, display: 'inline-grid', placeItems: 'center' }}>
      <svg className="ring" width={size} height={size} style={{ position: 'absolute', inset: 0, transform: 'rotate(-90deg)' }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--fill)" strokeWidth={stroke} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={tone(accent)} strokeWidth={stroke} strokeLinecap="round" strokeDasharray={`${c * share} ${c}`} />
      </svg>
      <span style={{ position: 'relative' }}>{children}</span>
    </span>
  );
}

const FACE_TONES = ['bad', 'stress', 'focus', 'cyan', 'recovery'];
const FACE_MOUTHS = ['M8 16.5c1.2-1.6 2.5-2.3 4-2.3s2.8.7 4 2.3', 'M8.5 16c1.1-.9 2.3-1.3 3.5-1.3s2.4.4 3.5 1.3', 'M8.5 15.5h7', 'M8.5 14.5c1.1.9 2.3 1.3 3.5 1.3s2.4-.4 3.5-1.3', 'M8 14c1.2 1.6 2.5 2.3 4 2.3s2.8-.7 4-2.3'];

/** Five faces, 1 (low) to 5 (good). */
export function FaceScale({ value, onPick, size = 46 }: { value: number | null; onPick: (v: number) => void; size?: number }) {
  return (
    <div className="faces">
      {FACE_TONES.map((t, i) => (
        <button key={t} className={`face${value === i + 1 ? ' on' : ''}`} style={toneStyle(t, { width: size, height: size })} aria-label={`${i + 1} of 5`} onClick={() => onPick(i + 1)}>
          <svg width={size * 0.6} height={size * 0.6} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M9 9.5v.5M15 9.5v.5" strokeWidth={2.4} />
            <path d={FACE_MOUTHS[i]} />
          </svg>
        </button>
      ))}
    </div>
  );
}

export function Hair() {
  return <hr className="hair" />;
}

/** A sheet's frame: the grabber, Cancel / title / a bold action, then the body. */
export function SheetFrame({ title, onCancel, cancel = 'Cancel', action, onAction, actionDisabled, children }: { title: string; onCancel?: () => void; cancel?: string; action?: string; onAction?: () => void; actionDisabled?: boolean; children: ReactNode }) {
  return (
    <>
      <div className="grabber" />
      <div className="sheet-head">
        <span className="side">{onCancel && <button className="link" onClick={onCancel}>{cancel}</button>}</span>
        <span className="t">{title}</span>
        <span className="side r">{action && <button className="link bold" disabled={actionDisabled} onClick={onAction} style={actionDisabled ? { color: 'var(--label3)' } : undefined}>{action}</button>}</span>
      </div>
      <div className="sheet-body">{children}</div>
    </>
  );
}
