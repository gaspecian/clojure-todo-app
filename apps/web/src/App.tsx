import { Routes, Route, Navigate } from 'react-router-dom'
import { SignupPage }     from './pages/SignupPage'
import { CallbackPage }   from './pages/CallbackPage'
import { TodosPage }      from './pages/TodosPage'
import { ProtectedRoute } from './components/ProtectedRoute'

export default function App() {
  return (
    <Routes>
      <Route path="/"         element={<Navigate to="/todos" replace />} />
      <Route path="/signup"   element={<SignupPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route path="/todos"    element={
        <ProtectedRoute>
          <TodosPage />
        </ProtectedRoute>
      } />
    </Routes>
  )
}
