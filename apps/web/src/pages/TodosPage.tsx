import { useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useTodos, type Todo } from '@/hooks/useTodos'
import { Button }  from '@/components/ui/button'
import { Input }   from '@/components/ui/input'
import { Sheet, SheetContent, SheetTrigger } from '@/components/ui/sheet'
import { Menu, Plus, Check, Pencil, Trash2 } from 'lucide-react'

const PRIORITY_COLORS: Record<string, string> = {
  high:   'bg-red-900 text-red-300',
  medium: 'bg-yellow-900 text-yellow-300',
  low:    'bg-green-900 text-green-300',
}

type PriorityLevel = Todo['priority']

const PRIORITY_LEVELS: PriorityLevel[] = ['low', 'medium', 'high']

function PrioritySelect({ value, onChange }: {
  value:    PriorityLevel
  onChange: (p: PriorityLevel) => void
}) {
  return (
    <select
      value={value}
      onChange={e => onChange(e.target.value as PriorityLevel)}
      className="bg-slate-800 border border-slate-700 text-slate-100 rounded-md text-sm px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500"
    >
      {PRIORITY_LEVELS.map(p => (
        <option key={p} value={p}>{p[0].toUpperCase() + p.slice(1)}</option>
      ))}
    </select>
  )
}

function TodoItem({ todo, onToggle, onUpdate, onDelete }: {
  todo:     Todo
  onToggle: (todo: Todo) => void
  onUpdate: (id: string, fields: { title: string; priority: PriorityLevel; body: string }) => void
  onDelete: (id: string) => void
}) {
  const [editing, setEditing]   = useState(false)
  const [title, setTitle]       = useState(todo.title)
  const [priority, setPriority] = useState<PriorityLevel>(todo.priority)
  const [body, setBody]         = useState(todo.body)

  function startEdit() {
    setTitle(todo.title)
    setPriority(todo.priority)
    setBody(todo.body)
    setEditing(true)
  }

  function save() {
    const trimmed = title.trim()
    if (!trimmed) return
    onUpdate(todo.id, { title: trimmed, priority, body: body.trim() })
    setEditing(false)
  }

  if (editing) {
    return (
      <div className="bg-slate-800 rounded-lg p-4 space-y-3">
        <Input
          value={title}
          onChange={e => setTitle(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') save(); if (e.key === 'Escape') setEditing(false) }}
          autoFocus
          placeholder="Title"
          className="bg-slate-950 border-slate-700 text-slate-100 placeholder:text-slate-500"
        />
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-400">Priority</span>
          <PrioritySelect value={priority} onChange={setPriority} />
        </div>
        <Input
          value={body}
          onChange={e => setBody(e.target.value)}
          placeholder="Notes (optional)"
          className="bg-slate-950 border-slate-700 text-slate-100 placeholder:text-slate-500"
        />
        <div className="flex gap-2">
          <Button onClick={save} className="bg-indigo-600 hover:bg-indigo-700">Save</Button>
          <Button onClick={() => setEditing(false)} className="bg-slate-700 hover:bg-slate-600 text-slate-200">Cancel</Button>
        </div>
      </div>
    )
  }

  return (
    <div className={`flex items-start gap-3 bg-slate-800 rounded-lg p-4 ${todo.completed ? 'opacity-50' : ''}`}>
      <button
        onClick={() => onToggle(todo)}
        className={`mt-0.5 w-5 h-5 rounded flex items-center justify-center border-2 flex-shrink-0 ${
          todo.completed ? 'bg-indigo-600 border-indigo-600' : 'border-slate-500'
        }`}
      >
        {todo.completed && <Check size={12} className="text-white" />}
      </button>
      <div className="flex-1 min-w-0">
        <p className={`font-medium text-slate-100 ${todo.completed ? 'line-through' : ''}`}>
          {todo.title}
        </p>
        {todo.body && <p className="text-sm text-slate-400 mt-0.5 truncate">{todo.body}</p>}
        <div className="flex flex-wrap gap-2 mt-2">
          <span className={`text-xs px-2 py-0.5 rounded-full ${PRIORITY_COLORS[todo.priority]}`}>
            {todo.priority}
          </span>
          {todo.tags.map(tag => (
            <span key={tag} className="text-xs px-2 py-0.5 rounded-full bg-indigo-950 text-indigo-300">
              #{tag}
            </span>
          ))}
        </div>
      </div>
      <button onClick={startEdit} className="text-slate-500 hover:text-indigo-400 flex-shrink-0" aria-label="Edit todo">
        <Pencil size={16} />
      </button>
      <button onClick={() => onDelete(todo.id)} className="text-slate-500 hover:text-red-400 flex-shrink-0" aria-label="Delete todo">
        <Trash2 size={16} />
      </button>
    </div>
  )
}

function CreateTodoForm({ onSubmit }: { onSubmit: (title: string, priority: PriorityLevel) => void }) {
  const [value, setValue]       = useState('')
  const [priority, setPriority] = useState<PriorityLevel>('medium')

  function submit() {
    const trimmed = value.trim()
    if (!trimmed) return
    onSubmit(trimmed, priority)
    setValue('')
    setPriority('medium')
  }

  return (
    <div className="flex gap-2">
      <Input
        value={value}
        onChange={e => setValue(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter') submit() }}
        placeholder="Add a new todo… (press Enter)"
        className="bg-slate-800 border-slate-700 text-slate-100 placeholder:text-slate-500"
      />
      <PrioritySelect value={priority} onChange={setPriority} />
      <Button onClick={submit} className="bg-indigo-600 hover:bg-indigo-700 flex-shrink-0">
        <Plus size={16} />
      </Button>
    </div>
  )
}

type Filter   = 'all' | 'active' | 'completed'
type Priority = 'all' | 'high' | 'medium' | 'low'

function Sidebar({ filter, setFilter, priority, setPriority }: {
  filter:      Filter
  setFilter:   (f: Filter) => void
  priority:    Priority
  setPriority: (p: Priority) => void
}) {
  return (
    <nav className="space-y-6">
      <div>
        <p className="text-xs text-slate-500 uppercase tracking-widest mb-2">Status</p>
        {(['all', 'active', 'completed'] as Filter[]).map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`block w-full text-left px-3 py-2 rounded-lg text-sm capitalize ${
              filter === f ? 'bg-slate-700 text-slate-100' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {f}
          </button>
        ))}
      </div>
      <div>
        <p className="text-xs text-slate-500 uppercase tracking-widest mb-2">Priority</p>
        {(['all', 'high', 'medium', 'low'] as Priority[]).map(p => (
          <button
            key={p}
            onClick={() => setPriority(p)}
            className={`block w-full text-left px-3 py-2 rounded-lg text-sm capitalize ${
              priority === p ? 'bg-slate-700 text-slate-100' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {p === 'all' ? 'All priorities' : p}
          </button>
        ))}
      </div>
    </nav>
  )
}

export function TodosPage() {
  const { logout } = useAuth()
  const { todos, isLoading, create, update, remove } = useTodos()
  const [filter,   setFilter]   = useState<Filter>('all')
  const [priority, setPriority] = useState<Priority>('all')
  const [search,   setSearch]   = useState('')

  const filtered = todos.filter(t => {
    if (filter === 'active'    && t.completed)  return false
    if (filter === 'completed' && !t.completed) return false
    if (priority !== 'all' && t.priority !== priority) return false
    if (search && !t.title.toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  const sidebarContent = (
    <Sidebar filter={filter} setFilter={setFilter} priority={priority} setPriority={setPriority} />
  )

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Sheet>
            <SheetTrigger className="md:hidden text-slate-400 hover:text-slate-200">
              <Menu size={20} />
            </SheetTrigger>
            <SheetContent side="left" className="bg-slate-900 border-slate-800 w-64 p-6">
              {sidebarContent}
            </SheetContent>
          </Sheet>
          <span className="font-bold text-slate-100">&#10003; TodoApp</span>
        </div>
        <button onClick={logout} className="text-sm text-slate-400 hover:text-red-400">
          Log out
        </button>
      </header>

      <div className="flex">
        <aside className="hidden md:block w-56 flex-shrink-0 border-r border-slate-800 p-6 min-h-[calc(100vh-57px)]">
          {sidebarContent}
        </aside>

        <main className="flex-1 p-4 md:p-6 max-w-2xl">
          <div className="space-y-4">
            <Input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search todos…"
              className="bg-slate-800 border-slate-700 text-slate-100 placeholder:text-slate-500"
            />
            <CreateTodoForm onSubmit={(title, priority) => create.mutate({ title, priority })} />
            {isLoading && <p className="text-slate-400 text-sm">Loading…</p>}
            <div className="space-y-2">
              {filtered.map(todo => (
                <TodoItem
                  key={todo.id}
                  todo={todo}
                  onToggle={t => update.mutate({ id: t.id, completed: !t.completed })}
                  onUpdate={(id, fields) => update.mutate({ id, ...fields })}
                  onDelete={id => remove.mutate(id)}
                />
              ))}
              {!isLoading && filtered.length === 0 && (
                <p className="text-slate-500 text-sm text-center py-8">No todos yet. Add one above.</p>
              )}
            </div>
          </div>
        </main>
      </div>
    </div>
  )
}
