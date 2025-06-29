#!/bin/bash

echo "=== Rust 任务调度器监控诊断报告 ==="
echo "时间: $(date)"
echo ""

echo "1. Worker 状态检查:"
echo "-------------------"
echo "Worker Pool Size:"
curl -s http://localhost:9000/metrics | grep "worker_pool_size" | grep -v "^#"
echo ""
echo "Worker Active Tasks:"
curl -s http://localhost:9000/metrics | grep "worker_active_tasks" | grep -v "^#"
echo ""

echo "2. 任务处理状态:"
echo "---------------"
echo "队列深度:"
curl -s http://localhost:9000/metrics | grep "queue_depth" | grep -v "^#"
echo ""
echo "任务状态分布:"
curl -s http://localhost:9000/metrics | grep "tasks_status_total" | grep -v "^#" | sort
echo ""
echo "已提交任务:"
curl -s http://localhost:9000/metrics | grep "tasks_submitted_total" | grep -v "^#" | sort
echo ""

echo "3. 性能指标:"
echo "-----------"
echo "执行时间统计 (非 bucket):"
curl -s http://localhost:9000/metrics | grep "task_execution_duration_seconds" | grep -v "^#" | grep -v "bucket" | head -10
echo ""

echo "4. 错误和重试:"
echo "-------------"
echo "错误分类:"
curl -s http://localhost:9000/metrics | grep "tasks_errors_by_category" | grep -v "^#"
echo ""
echo "任务重试:"
curl -s http://localhost:9000/metrics | grep "task_retries" | grep -v "^#"
echo ""

echo "5. 系统资源:"
echo "-----------"
echo "CPU 使用率 (总体):"
curl -s http://localhost:9000/metrics | grep "cpu_usage_percent{core=\"total\"}" | grep -v "^#"
echo ""
echo "内存使用:"
curl -s http://localhost:9000/metrics | grep "memory_usage_bytes" | grep -v "^#"
echo ""

echo "6. 存储操作:"
echo "-----------"
curl -s http://localhost:9000/metrics | grep "storage_operations_total" | grep -v "^#" | head -5
echo ""

echo "7. 计算利用率:"
echo "-------------"
# 计算实际的 worker 利用率
active_tasks=$(curl -s http://localhost:9000/metrics | grep "worker_active_tasks" | grep -v "^#" | grep -v "placeholder" | awk '{sum+=$2} END {print sum}')
pool_size=$(curl -s http://localhost:9000/metrics | grep "worker_pool_size" | grep -v "^#" | awk '{print $2}')
max_tasks_per_worker=10  # 假设每个 worker 最多 10 个并发任务
total_capacity=$((pool_size * max_tasks_per_worker))

if [ "$total_capacity" -gt 0 ]; then
    utilization=$(echo "scale=2; $active_tasks * 100 / $total_capacity" | bc)
    echo "Worker Pool 利用率: ${utilization}% ($active_tasks / $total_capacity)"
else
    echo "Worker Pool 利用率: 无法计算 (pool_size = 0)"
fi

echo ""
echo "=== 诊断完成 ==="