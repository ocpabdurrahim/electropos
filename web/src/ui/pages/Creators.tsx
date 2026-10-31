// The app owner's list of people allowed to put a shop online (creators/{email}).
import { useEffect, useState } from 'react'
import { addCreator, removeCreator, watchCreators, type Creator } from '../../data/shops'
import { formatDate } from '../../domain/dates'
import { useFirebase } from '../App'
import { ConfirmDialog, Empty, ErrorText, Field, message, PageHead, useToast } from '../common'
import { Icon } from '../icons'

export default function Creators({ me, onBack }: { me: string; onBack?: () => void }) {
  const { db } = useFirebase()
  const toast = useToast()
  const [list, setList] = useState<Creator[]>([])
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<Creator | null>(null)
  useEffect(() => watchCreators(db, setList), [db])
  const add = async () => {
    setError(null)
    const e = email.trim().toLowerCase()
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) { setError('Type a Google email address.'); return }
    try {
      await addCreator(db, e, name, me)
      toast(`${e} may now put a shop online`)
      setEmail('')
      setName('')
    } catch (err) {
      setError(message(err))
    }
  }
  return (
    <div>
      <PageHead title="Shop creators" sub="People who may put a shop online from the phone app" back={onBack} />
      <div className="card pad stack" style={{ marginBottom: 16 }}>
        <div className="form-grid">
          <Field label="Google email"><input className="input" type="email" value={email} onChange={e => setEmail(e.target.value)} data-testid="creator-email" /></Field>
          <Field label="Name"><input className="input" value={name} onChange={e => setName(e.target.value)} /></Field>
        </div>
        <ErrorText error={error} />
        <div><button className="btn" onClick={add} data-testid="creator-add"><Icon name="plus" size={18} />Allow</button></div>
        <p className="small muted" style={{ margin: 0 }}>You ({me}) can always create shops. Removing someone stops new shops only; shops they made stay.</p>
      </div>
      <div className="card list">
        {list.length === 0 && <Empty emoji="🔑" title="Nobody else yet" />}
        {list.map(c => (
          <div key={c.email} className="item">
            <div className="grow"><div className="title">{c.email}</div><div className="subtitle">{c.name || '—'} · allowed {formatDate(c.approvedAt)} by {c.approvedBy}</div></div>
            <button className="icon-btn" onClick={() => setRemoving(c)} aria-label="Remove"><Icon name="trash" size={18} /></button>
          </div>
        ))}
      </div>
      {removing && (
        <ConfirmDialog title="Remove this person?" danger confirm="Remove" text={`${removing.email} will no longer be able to put new shops online.`}
          onConfirm={() => void removeCreator(db, removing.email).catch(e => setError(message(e)))} onClose={() => setRemoving(null)} />
      )}
    </div>
  )
}
