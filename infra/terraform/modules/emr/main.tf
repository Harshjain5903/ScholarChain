################################################################################
# EMR Cluster
################################################################################

locals {
  prefix = "${var.project_name}-${var.environment}"
}

resource "aws_emr_cluster" "main" {
  name          = "${local.prefix}-cluster"
  release_label = var.release_label
  applications  = ["Hadoop", "Spark", "Hive"]

  service_role = var.emr_service_role_arn

  ec2_attributes {
    instance_profile                  = var.emr_ec2_instance_profile_arn
    subnet_id                         = var.subnet_id
    emr_managed_master_security_group = var.master_security_group_id
    emr_managed_slave_security_group  = var.core_security_group_id
  }

  master_instance_group {
    instance_type  = var.core_instance_type
    instance_count = 1
    name           = "master"
  }

  core_instance_group {
    instance_type  = var.core_instance_type
    instance_count = var.core_min_nodes
    name           = "core"

    autoscaling_policy = jsonencode({
      Constraints = {
        MinCapacity = var.core_min_nodes
        MaxCapacity = var.core_max_nodes
      }
      Rules = [
        {
          Name        = "ScaleOutOnYARNMemory"
          Description = "Scale out when YARN memory available is low"
          Action = {
            SimpleScalingPolicyConfiguration = {
              AdjustmentType    = "CHANGE_IN_CAPACITY"
              ScalingAdjustment = 2
              CoolDown          = 300
            }
          }
          Trigger = {
            CloudWatchAlarmDefinition = {
              MetricName         = "YARNMemoryAvailablePercentage"
              ComparisonOperator = "LESS_THAN"
              Threshold          = 15.0
              Period             = 300
              EvaluationPeriods  = 1
              Namespace          = "AWS/ElasticMapReduce"
              Statistic          = "AVERAGE"
              Unit               = "PERCENT"
              Dimensions = [{
                Key   = "JobFlowId"
                Value = "$${emr.clusterId}"
              }]
            }
          }
        },
        {
          Name        = "ScaleInOnYARNMemory"
          Description = "Scale in when YARN memory available is high"
          Action = {
            SimpleScalingPolicyConfiguration = {
              AdjustmentType    = "CHANGE_IN_CAPACITY"
              ScalingAdjustment = -1
              CoolDown          = 300
            }
          }
          Trigger = {
            CloudWatchAlarmDefinition = {
              MetricName         = "YARNMemoryAvailablePercentage"
              ComparisonOperator = "GREATER_THAN"
              Threshold          = 75.0
              Period             = 300
              EvaluationPeriods  = 1
              Namespace          = "AWS/ElasticMapReduce"
              Statistic          = "AVERAGE"
              Unit               = "PERCENT"
              Dimensions = [{
                Key   = "JobFlowId"
                Value = "$${emr.clusterId}"
              }]
            }
          }
        },
      ]
    })
  }

  log_uri = "s3://${var.logs_bucket}/emr-logs/"

  configurations_json = jsonencode([
    {
      Classification = "spark-defaults"
      Properties = {
        "spark.dynamicAllocation.enabled" = "true"
        "spark.shuffle.service.enabled"   = "true"
        "spark.serializer"                = "org.apache.spark.serializer.KryoSerializer"
      }
    },
    {
      Classification = "yarn-site"
      Properties = {
        "yarn.resourcemanager.am.max-attempts" = "3"
      }
    },
    {
      Classification = "mapred-site"
      Properties = {
        "mapreduce.map.memory.mb"    = "2048"
        "mapreduce.reduce.memory.mb" = "4096"
      }
    },
  ])

  bootstrap_action {
    path = "s3://${var.logs_bucket}/bootstrap/install-dependencies.sh"
    name = "install-dependencies"
  }

  auto_termination_policy {
    idle_timeout = var.environment == "prod" ? 7200 : 3600
  }

  tags = {
    Name = "${local.prefix}-cluster"
  }
}

################################################################################
# EMR Step Definitions (templates for submission)
################################################################################

# Step configs are submitted via scripts/run-bounded-pipeline.sh (N42).
# These locals document the expected step shapes. The hadoop assembly has no
# Main-Class, so every hadoop step names the driver class explicitly through
# command-runner.jar. Per-job input/output and -D values are supplied at
# submission time by the script.

locals {
  artifacts_jar_prefix = "s3://${var.project_name}-${var.environment}-artifacts/jars"

  hadoop_step_config = {
    name              = "ScholarChain-Hadoop-<job>"
    action_on_failure = "CONTINUE"
    hadoop_jar_step = {
      jar = "command-runner.jar"
      args = [
        "hadoop", "jar", "${local.artifacts_jar_prefix}/scholarchain-hadoop.jar",
        "edu.scholarchain.hadoop.driver.HadoopJobDriver",
        "<job>",
        "-Dscholarchain.pipeline.run-id=<base>-hadoop",
        "-Dscholarchain.pipeline.schema-version=1.0.0",
        "-Dscholarchain.run.timestamp=<ISO8601>",
        "<input>", "<output>",
      ]
    }
  }

  # Needs an entity-resolution assembly (not built yet; build-engineer
  # follow-up). The runner reads local paths only, so the script stages S3
  # data to the master's disk with `bash -c`.
  er_step_config = {
    name              = "ScholarChain-EntityResolution"
    action_on_failure = "CONTINUE"
    hadoop_jar_step = {
      jar = "command-runner.jar"
      args = [
        "java", "-cp", "${local.artifacts_jar_prefix}/scholarchain-entity-resolution.jar",
        "edu.scholarchain.entityresolution.EntityResolutionRunner",
        "--work-records", "<export>/work_records", "--output", "<dir>",
        "--run-id", "<base>-er", "--input-run-id", "<base>-hadoop",
      ]
    }
  }

  spark_step_config = {
    name              = "ScholarChain-Spark-GraphBuild"
    action_on_failure = "CONTINUE"
    hadoop_jar_step = {
      jar = "command-runner.jar"
      args = [
        "spark-submit",
        "--deploy-mode", "cluster",
        "--class", "edu.scholarchain.spark.ScholarChainPipeline",
        "${local.artifacts_jar_prefix}/scholarchain-spark.jar",
        "--hadoop-output", "<export>",
        "--match-decisions", "<er-output>/match-decisions",
        "--output", "<new s3 path>",
        "--run-id", "<base>-spark",
        # no --policy-version: derived from the ER decisions
      ]
    }
  }
}
