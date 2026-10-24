import type { Task } from '@shared/models'
import { TASK_TABLES, getActiveTask } from '@shared/repos/tasks'
import { Loading, TopBar } from '../components/ui'
import { db, useQuery } from '../lib/hooks'
import { DurationTaskScreen } from './tasks/DurationTask'
import { GpsTaskScreen } from './tasks/GpsTask'
import { JournalTaskScreen } from './tasks/JournalTask'
import { MeditationTaskScreen } from './tasks/MeditationTask'
import { ReportTaskScreen } from './tasks/ReportTask'
import { RoutineTaskScreen } from './tasks/RoutineTask'
import { ShoppingListScreen } from './tasks/ShoppingList'
import { SimpleTaskScreen } from './tasks/SimpleTask'

export function useTask(taskId: string) {
  return useQuery(() => getActiveTask(db, taskId), TASK_TABLES, [taskId], null as Task | null)
}

/** Opens the right screen for a task — Android's onTaskTap mapping. */
export function TaskScreen({ taskId }: { taskId: string }) {
  const { data: task, loading } = useTask(taskId)
  if (loading) return <Loading />
  if (!task) {
    return (
      <>
        <TopBar title="Task" />
        <div className="empty">This task no longer exists.</div>
      </>
    )
  }
  // Shopping before GPS: shopping tasks also carry verification_type "gps".
  if (task.taskType === 'shopping') return <ShoppingListScreen task={task} />
  if (task.verificationType === 'gps') return <GpsTaskScreen task={task} />
  if (task.verificationType === 'duration') return <DurationTaskScreen task={task} />
  if (task.verificationType === 'meditation') return <MeditationTaskScreen task={task} />
  if (task.taskType === 'routine' || task.taskType === 'workout') return <RoutineTaskScreen task={task} />
  if (task.taskType === 'journal') return <JournalTaskScreen task={task} />
  if (task.taskType === 'report') return <ReportTaskScreen task={task} />
  return <SimpleTaskScreen task={task} />
}
