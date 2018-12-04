package com.dfire.common.service.impl;

import com.dfire.common.constants.Constants;
import com.dfire.common.entity.HeraGroup;
import com.dfire.common.entity.HeraJob;
import com.dfire.common.entity.HeraJobHistory;
import com.dfire.common.entity.vo.HeraJobTreeNodeVo;
import com.dfire.common.mapper.HeraJobMapper;
import com.dfire.common.service.HeraGroupService;
import com.dfire.common.service.HeraJobHistoryService;
import com.dfire.common.service.HeraJobService;
import com.dfire.common.util.DagLoopUtil;
import com.dfire.common.vo.RestfulResponse;
import com.dfire.graph.DirectionGraph;
import com.dfire.graph.Edge;
import com.dfire.graph.GraphNode;
import com.dfire.graph.JobRelation;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xiaosuda
 * @date 2018/11/7
 */
@Service("heraJobService")
public class HeraJobServiceImpl implements HeraJobService {

    @Autowired
    private HeraJobMapper heraJobMapper;
    @Autowired
    private HeraGroupService groupService;
    @Autowired
    private HeraJobHistoryService heraJobHistoryService;

    @Override
    public int insert(HeraJob heraJob) {
        Date date = new Date();
        heraJob.setGmtCreate(date);
        heraJob.setGmtModified(date);
        heraJob.setAuto(0);
        return heraJobMapper.insert(heraJob);
    }

    @Override
    public int delete(int id) {
        return heraJobMapper.delete(id);
    }

    @Override
    public Integer update(HeraJob heraJob) {
        return heraJobMapper.update(heraJob);
    }

    @Override
    public List<HeraJob> getAll() {
        return heraJobMapper.getAll();
    }

    @Override
    public HeraJob findById(int id) {
        HeraJob heraJob = HeraJob.builder().id(id).build();
        return heraJobMapper.findById(heraJob);
    }

    @Override
    public List<HeraJob> findByIds(List<Integer> list) {
        return heraJobMapper.findByIds(list);
    }

    @Override
    public List<HeraJob> findByPid(int groupId) {
        HeraJob heraJob = HeraJob.builder().groupId(groupId).build();
        return heraJobMapper.findByPid(heraJob);
    }

    @Override
    public Map<String, List<HeraJobTreeNodeVo>> buildJobTree(String owner) {

        Map<String, List<HeraJobTreeNodeVo>> treeMap = new HashMap<>(2);
        List<HeraGroup> groups = groupService.getAll();
        List<HeraJob> jobs = heraJobMapper.selectAll();
        Map<String, HeraJobTreeNodeVo> groupMap = new HashMap<>(groups.size());
        // 建立所有任务的树
        List<HeraJobTreeNodeVo> allNodes = groups.stream()
                .filter(group -> group.getExisted() == 1)
                .map(g -> {
                    HeraJobTreeNodeVo groupNodeVo = HeraJobTreeNodeVo.builder()
                            .id(Constants.GROUP_PREFIX + g.getId())
                            .parent(Constants.GROUP_PREFIX + g.getParent())
                            .directory(g.getDirectory())
                            .isParent(true)
                            .name(g.getName() + Constants.LEFT_BRACKET + g.getId() + Constants.RIGHT_BRACKET)
                            .build();
                    groupMap.put(groupNodeVo.getId(), groupNodeVo);
                    return groupNodeVo;
                })
                .collect(Collectors.toList());
        allNodes.addAll(jobs.stream().map(job -> HeraJobTreeNodeVo.builder()
                .id(String.valueOf(job.getId()))
                .parent(Constants.GROUP_PREFIX + job.getGroupId())
                .isParent(false)
                .name(job.getName() + Constants.LEFT_BRACKET + job.getId() + Constants.RIGHT_BRACKET)
                .build())
                .collect(Collectors.toList()));

        Set<HeraJobTreeNodeVo> myGroupSet = new HashSet<>();
        //建立我的任务的树
        List<HeraJobTreeNodeVo> myNodeVos = jobs.stream()
                .filter(job -> owner.equals(job.getOwner().trim()))
                .map(job -> {
                    HeraJobTreeNodeVo build = HeraJobTreeNodeVo.builder()
                            .id(String.valueOf(job.getId()))
                            .parent(Constants.GROUP_PREFIX + job.getGroupId())
                            .isParent(false)
                            .name(job.getName() + Constants.LEFT_BRACKET + job.getId() + Constants.RIGHT_BRACKET)
                            .build();
                    getPathGroup(myGroupSet, build.getParent(), groupMap);
                    return build;
                })
                .collect(Collectors.toList());
        myNodeVos.addAll(myGroupSet);
        //添加树到返回结果
        allNodes.sort(Comparator.comparing(x -> x.getName().trim()));
        myNodeVos.sort(Comparator.comparing(x -> x.getName().trim()));
        treeMap.put("myJob", myNodeVos);
        treeMap.put("allJob", allNodes);
        return treeMap;
    }

    private void getPathGroup(Set<HeraJobTreeNodeVo> myGroupSet, String group, Map<String, HeraJobTreeNodeVo> allGroupMap) {
        HeraJobTreeNodeVo groupNode = allGroupMap.get(group);
        if (groupNode == null) {
            return;
        }
        myGroupSet.add(groupNode);
        getPathGroup(myGroupSet, groupNode.getParent(), allGroupMap);

    }

    @Override
    public boolean changeSwitch(Integer id) {
        Integer res = heraJobMapper.updateSwitch(id);
        return res != null && res > 0;
    }

    @Override
    public RestfulResponse checkAndUpdate(HeraJob heraJob) {


        if (StringUtils.isNotBlank(heraJob.getDependencies())) {
            HeraJob job = this.findById(heraJob.getId());

            if (!heraJob.getDependencies().equals(job.getDependencies())) {
                List<HeraJob> relation = heraJobMapper.getAllJobRelation();

                DagLoopUtil dagLoopUtil = new DagLoopUtil(heraJobMapper.selectMaxId());
                relation.forEach(x -> {
                    String dependencies;
                    if (x.getId() == heraJob.getId()) {
                        dependencies = heraJob.getDependencies();
                    } else {
                        dependencies = x.getDependencies();
                    }
                    if (StringUtils.isNotBlank(dependencies)) {
                        String[] split = dependencies.split(",");
                        for (String s : split) {
                            dagLoopUtil.addEdge(x.getId(), Integer.parseInt(s));
                        }
                    }
                });

                if (dagLoopUtil.isLoop()) {
                    return new RestfulResponse(false, "出现环形依赖，请检测依赖关系:" + dagLoopUtil.getLoop());
                }
            }
        }

        Integer line = this.update(heraJob);
        if (line == null || line == 0) {
            return new RestfulResponse(false, "更新失败，请联系管理员");
        }
        return new RestfulResponse(true, "更新成功");


    }

    @Override
    public Map<String, Object> findCurrentJobGraph(int jobId, Integer type) {
        Map<String, GraphNode> historyMap = buildHistoryMap();
        HeraJob nodeJob = findById(jobId);
        if (nodeJob == null) {
            return null;
        }
        GraphNode graphNode1 = historyMap.get(nodeJob.getId() + "");
        String remark = "";
        if (graphNode1 != null) {
            remark = (String) graphNode1.getRemark();
        }
        GraphNode<Integer> graphNode = new GraphNode<>(nodeJob.getAuto(),nodeJob.getId(), "任务ID：" + jobId + "\n任务名称:" + nodeJob.getName() + remark);
        return buildCurrJobGraph(historyMap, graphNode, getDirectionGraph(), type);
    }

    @Override
    public List<JobRelation> getJobRelations() {
        List<JobRelation> list = heraJobMapper.getJobRelations();
        List<JobRelation> res = new ArrayList<>(list.size() * 3);
        Map<String, String> map = new HashMap<>(list.size());

        for (JobRelation r : list) {
            String id = r.getId();
            String name = r.getName();
            map.put(id, name);
        }
        for (JobRelation r : list) {
            String id = r.getId();
            String dependencies = r.getDependencies();
            if (dependencies == null || dependencies.equals("")) {
                continue;
            }
            String[] ds = dependencies.split(",");
            for (String d : ds) {
                if (map.get(d) == null) {
                    continue;
                }
                JobRelation jr = new JobRelation();
                jr.setAuto(r.getAuto());
                jr.setId(id);
                jr.setName(map.get(id));
                jr.setPid(d);
                jr.setPname(map.get(d));
                res.add(jr);
            }
        }
        return res;
    }

    @Override
    public List<HeraJob> findAllDependencies() {
        return heraJobMapper.findAllDependencies();
    }

    @Override
    public List<HeraJob> findDownStreamJob(Integer jobId) {
        return this.getStreamTask(jobId, true);
    }

    @Override
    public List<HeraJob> findUpStreamJob(Integer jobId) {
        return this.getStreamTask(jobId, false);

    }

    /**
     * 建立今日任务执行 Map映射 便于获取
     *
     * @return Map
     */
    private Map<String, GraphNode> buildHistoryMap() {

        List<HeraJobHistory> actionHistories = heraJobHistoryService.findTodayJobHistory();
        Map<String, GraphNode> map = new HashMap<>(actionHistories.size());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (HeraJobHistory actionHistory : actionHistories) {
            String start = "none", end = "none", status, jobId, duration;
            status = actionHistory.getStatus() == null ? "none" : actionHistory.getStatus();
            jobId = actionHistory.getJobId() + "";
            duration = "none";
            if (actionHistory.getStartTime() != null) {
                start = sdf.format(actionHistory.getStartTime());
                if (actionHistory.getEndTime() != null) {
                    duration = (actionHistory.getEndTime().getTime() - actionHistory.getStartTime().getTime()) / 1000 + "s";
                    end = sdf.format(actionHistory.getEndTime());
                }
            }
            GraphNode node = new GraphNode<>(Integer.parseInt(jobId),
                    "任务状态：" + status + "\n" +
                            "执行时间：" + start + "\n" +
                            "结束时间：" + end + "\n" +
                            "耗时：" + duration + "\n");

            map.put(actionHistory.getJobId() + "",node );
        }
        return map;
    }

    private DirectionGraph<Integer> getDirectionGraph() {
        return this.buildJobGraph(this.getJobRelations());
    }


    /**
     * 获得上下游的任务
     *
     * @param jobId 任务id
     * @param down  是否为下游
     * @return
     */

    private List<HeraJob> getStreamTask(Integer jobId, boolean down) {
        GraphNode<Integer> head = new GraphNode<>();
        head.setNodeName(jobId);
        DirectionGraph<Integer> graph = this.getDirectionGraph();
        Integer headIndex = graph.getNodeIndex(head);
        Queue<Integer> nodeQueue = new LinkedList<>();
        nodeQueue.add(headIndex);
        ArrayList<Integer> graphNodes;
        Map<Integer, GraphNode<Integer>> indexMap = graph.getIndexMap();
        List<Integer> jobList = new ArrayList<>();
        while (!nodeQueue.isEmpty()) {
            headIndex = nodeQueue.remove();
            if (down) {
                graphNodes = graph.getTarEdge()[headIndex];
            } else {
                graphNodes = graph.getSrcEdge()[headIndex];
            }
            if (graphNodes == null || graphNodes.size() == 0) {
                continue;
            }

            for (Integer graphNode : graphNodes) {
                nodeQueue.add(graphNode);
                jobList.add(indexMap.get(graphNode).getNodeName());
            }
        }

        List<HeraJob> res = new ArrayList<>();
        for (Integer id : jobList) {
            res.add(this.findById(id));
        }
        return res;
    }

    /**
     * @param historyMap 宙斯任务历史运行任务map
     * @param node       当前头节点
     * @param graph      所有任务的关系图
     * @param type       展示类型  0:任务进度分析   1：影响分析
     */
    private Map<String, Object> buildCurrJobGraph(Map<String, GraphNode> historyMap, GraphNode<Integer> node, DirectionGraph<Integer> graph, Integer type) {
        String start = "start_node";
        Map<String, Object> res = new HashMap<>(2);
        List<Edge> edgeList = new ArrayList<>();
        Queue<GraphNode<Integer>> nodeQueue = new LinkedList<>();
        GraphNode headNode = new GraphNode<>(0, start);
        res.put("headNode", headNode);
        nodeQueue.add(node);
        edgeList.add(new Edge(headNode, node));
        ArrayList<Integer> graphNodes;
        Map<Integer, GraphNode<Integer>> indexMap = graph.getIndexMap();
        GraphNode graphNode;
        Integer index;
        while (!nodeQueue.isEmpty()) {
            node = nodeQueue.remove();
            index = graph.getNodeIndex(node);
            if (index == null) {
                break;
            }
            if (type == 0) {
                graphNodes = graph.getSrcEdge()[index];
            } else {
                graphNodes = graph.getTarEdge()[index];
            }
            if (graphNodes == null) {
                continue;
            }
            for (Integer integer : graphNodes) {
                graphNode = indexMap.get(integer);
                GraphNode graphNode1 = historyMap.get(graphNode.getNodeName() + "");
                if (graphNode1 == null) {
                    graphNode1 = new GraphNode<>(graphNode.getAuto(),graphNode.getNodeName(), "" + graphNode.getRemark());
                } else {
                    graphNode1 = new GraphNode<>(graphNode.getAuto(),graphNode.getNodeName(), "" + graphNode.getRemark() + graphNode1.getRemark());
                }
                edgeList.add(new Edge(node, graphNode1));
                nodeQueue.add(graphNode1);
            }
        }
        res.put("edges", edgeList);
        return res;
    }


    /**
     * 定时调用的任务图
     *
     * @param jobRelations 任务之间的关系
     * @return DirectionGraph
     */

    public DirectionGraph<Integer> buildJobGraph(List<JobRelation> jobRelations) {
        DirectionGraph<Integer> directionGraph = new DirectionGraph<>();
        for (JobRelation jobRelation : jobRelations) {
            GraphNode<Integer> graphNodeTwo = new GraphNode<>(jobRelation.getAuto(),Integer.parseInt(jobRelation.getPid()), "任务ID：" + jobRelation.getPid() + "\n任务名称：" + jobRelation.getPname() + "\n");
            GraphNode<Integer> graphNodeOne = new GraphNode<>(jobRelation.getAuto(),Integer.parseInt(jobRelation.getId()), "任务ID：" + jobRelation.getId() + "\n任务名称：" + jobRelation.getName() + "\n");
            directionGraph.addNode(graphNodeOne);
            directionGraph.addNode(graphNodeTwo);
            directionGraph.addEdge(graphNodeOne, graphNodeTwo);
        }
        return directionGraph;
    }


}
