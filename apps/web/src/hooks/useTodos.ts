import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface Todo {
  id:        string
  title:     string
  body:      string
  completed: boolean
  priority:  'low' | 'medium' | 'high'
  due_date:  string | null
  tags:      string[]
}

interface CreateTodoInput {
  title:     string
  body?:     string
  priority?: 'low' | 'medium' | 'high'
  tags?:     string[]
}

interface UpdateTodoInput extends Partial<CreateTodoInput> {
  completed?: boolean
}

export function useTodos() {
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['todos'],
    queryFn:  () => api.get<{ todos: Todo[] }>('/api/todos').then(r => r.todos),
  })

  const create = useMutation({
    mutationFn: (input: CreateTodoInput) => api.post<Todo>('/api/todos', input),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  const update = useMutation({
    mutationFn: ({ id, ...input }: UpdateTodoInput & { id: string }) =>
      api.put<Todo>(`/api/todos/${id}`, input),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/todos/${id}`),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  return { todos: data ?? [], isLoading, create, update, remove }
}
