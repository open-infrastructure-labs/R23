import os
from spark_tensorflow_distributor import MirroredStrategyRunner
from pyspark.sql import SparkSession

# Taken from https://github.com/tensorflow/ecosystem/tree/master/spark/spark-tensorflow-distributor#examples
def train():
    import tensorflow as tf
    import uuid

    BUFFER_SIZE = 10000
    BATCH_SIZE = 64

    def make_datasets():
        (mnist_images, mnist_labels), _ = \
            tf.keras.datasets.mnist.load_data(path=str(uuid.uuid4())+'mnist.npz')

        dataset = tf.data.Dataset.from_tensor_slices((
            tf.cast(mnist_images[..., tf.newaxis] / 255.0, tf.float32),
            tf.cast(mnist_labels, tf.int64))
        )
        dataset = dataset.repeat().shuffle(BUFFER_SIZE).batch(BATCH_SIZE)
        return dataset

    def build_and_compile_cnn_model():
        model = tf.keras.Sequential([
            tf.keras.layers.Conv2D(32, 3, activation='relu', input_shape=(28, 28, 1)),
            tf.keras.layers.MaxPooling2D(),
            tf.keras.layers.Flatten(),
            tf.keras.layers.Dense(64, activation='relu'),
            tf.keras.layers.Dense(10, activation='softmax'),
        ])
        model.compile(
            loss=tf.keras.losses.sparse_categorical_crossentropy,
            optimizer=tf.keras.optimizers.SGD(learning_rate=0.001),
            metrics=['accuracy'],
        )
        return model

    model_path = '/tmp/keras-model-2'

    def _is_chief(task_type, task_id):
        # Note: there are two possible `TF_CONFIG` configurations.
        #   1) In addition to `worker` tasks, a `chief` task type is use;
        #      in this case, this function should be modified to
        #      `return task_type == 'chief'`.
        #   2) Only `worker` task type is used; in this case, worker 0 is
        #      regarded as the chief. The implementation demonstrated here
        #      is for this case.
        # For the purpose of this Colab section, the `task_type` is `None` case
        # is added because it is effectively run with only a single worker.
        return (task_type == 'worker' and task_id == 0) or task_type is None

    def _get_temp_dir(dirpath, task_id):
        base_dirpath = 'workertemp_' + str(task_id)
        temp_dir = os.path.join(dirpath, base_dirpath)
        tf.io.gfile.makedirs(temp_dir)
        return temp_dir

    def write_filepath(filepath, task_type, task_id):
        dirpath = os.path.dirname(filepath)
        base = os.path.basename(filepath)
        if not _is_chief(task_type, task_id):
            dirpath = _get_temp_dir(dirpath, task_id)
        return os.path.join(dirpath, base)

    train_datasets = make_datasets()
    options = tf.data.Options()
    options.experimental_distribute.auto_shard_policy = tf.data.experimental.AutoShardPolicy.DATA
    train_datasets = train_datasets.with_options(options)
    multi_worker_model = build_and_compile_cnn_model()
    task_id = multi_worker_model.distribute_strategy.cluster_resolver.task_id
    task_type = multi_worker_model.distribute_strategy.cluster_resolver.task_type
    print(f"Start Fit {task_type} {task_id}")
    multi_worker_model.fit(x=train_datasets, epochs=3, steps_per_epoch=5)
    print(f"Done {task_type} {task_id}")
    if task_id == None or (task_type == "worker" and task_id == 0):
        # Only save on chief worker.
        print(f"Saving Model for: {task_type} {task_id}")
        write_model_path = write_filepath(model_path, task_type, task_id)
        multi_worker_model.save(write_model_path, overwrite=True)
        print(f"Saving Model Complete for: {task_type} {task_id}")

spark = SparkSession.builder.getOrCreate()
sc = spark.sparkContext
sc.setLogLevel("INFO")
print("Starting Training")
model = MirroredStrategyRunner(num_slots=1, use_gpu=False, local_mode=True).run(train)
print("Done training")
