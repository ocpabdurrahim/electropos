import { NavLink } from 'react-router-dom'
import { useShop } from '../../data/store'
import { can } from '../../domain/roles'
import { NAV } from '../App'
import { PageHead } from '../common'
import { Icon } from '../icons'

export default function More() {
  const { role } = useShop()
  return (
    <div className="page">
      <PageHead title="More" />
      <div className="card list">
        {NAV.filter(n => !n.feature || can(role, n.feature)).map(n => (
          <NavLink key={n.to} to={n.to} className="item"><Icon name={n.icon} /><div className="grow title">{n.label}</div><Icon name="right" size={18} /></NavLink>
        ))}
      </div>
    </div>
  )
}
