package ch.epfl.data.plan_runner.storm_components.theta;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import backtype.storm.Config;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.InputDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Tuple;
import ch.epfl.data.plan_runner.components.ComponentProperties;
import ch.epfl.data.plan_runner.predicates.ComparisonPredicate;
import ch.epfl.data.plan_runner.predicates.Predicate;
import ch.epfl.data.plan_runner.storage.BPlusTreeStorage;
import ch.epfl.data.plan_runner.storm_components.InterchangingComponent;
import ch.epfl.data.plan_runner.storm_components.StormComponent;
import ch.epfl.data.plan_runner.storm_components.StormEmitter;
import ch.epfl.data.plan_runner.storm_components.StormJoinerBoltComponent;
import ch.epfl.data.plan_runner.storm_components.synchronization.TopologyKiller;
import ch.epfl.data.plan_runner.thetajoin.matrix_mapping.EquiMatrixAssignment;
import ch.epfl.data.plan_runner.utilities.MyUtilities;
import ch.epfl.data.plan_runner.utilities.PeriodicAggBatchSend;
import ch.epfl.data.plan_runner.utilities.SystemParameters;
import ch.epfl.data.plan_runner.utilities.statistics.StatisticsUtilities;
import ch.epfl.data.plan_runner.window_semantics.WindowSemanticsManager;

public class StormThetaJoinBDB extends StormJoinerBoltComponent {
	private static final long serialVersionUID = 1L;
	private static Logger LOG = Logger.getLogger(StormThetaJoinBDB.class);
	private BPlusTreeStorage _firstRelationStorage, _secondRelationStorage;

	public StormThetaJoinBDB(StormEmitter firstEmitter,
			StormEmitter secondEmitter, ComponentProperties cp,
			List<String> allCompNames, Predicate joinPredicate,
			int hierarchyPosition, TopologyBuilder builder,
			TopologyKiller killer, Config conf, InterchangingComponent interComp) {
		super(firstEmitter, secondEmitter, cp, allCompNames, joinPredicate,
				hierarchyPosition, builder, killer, conf);
		_statsUtils = new StatisticsUtilities(getConf(), LOG);
		final int firstCardinality = SystemParameters.getInt(conf,
				firstEmitter.getName() + "_CARD");
		final int secondCardinality = SystemParameters.getInt(conf,
				secondEmitter.getName() + "_CARD");
		final int parallelism = SystemParameters.getInt(conf, getID() + "_PAR");
		InputDeclarer currentBolt = builder.setBolt(getID(), this, parallelism);
		final EquiMatrixAssignment _currentMappingAssignment = new EquiMatrixAssignment(
				firstCardinality, secondCardinality, parallelism, -1);
		final String dim = _currentMappingAssignment.getMappingDimensions();
		LOG.info(getID() + " Initial Dimensions is: " + dim);
		if (interComp == null)
			currentBolt = MyUtilities.thetaAttachEmitterComponents(currentBolt,
					firstEmitter, secondEmitter, allCompNames,
					_currentMappingAssignment, conf,
					((ComparisonPredicate) joinPredicate).getwrapper());
		else {
			currentBolt = MyUtilities
					.thetaAttachEmitterComponentsWithInterChanging(currentBolt,
							firstEmitter, secondEmitter, allCompNames,
							_currentMappingAssignment, conf, interComp);
			_inter = interComp;
		}
		if (getHierarchyPosition() == FINAL_COMPONENT
				&& (!MyUtilities.isAckEveryTuple(conf)))
			killer.registerComponent(this, parallelism);
		if (cp.getPrintOut() && _operatorChain.isBlocking())
			currentBolt.allGrouping(killer.getID(),
					SystemParameters.DUMP_RESULTS_STREAM);
		LOG.info("Storage with BDB!");
	}

	@Override
	public void execute(Tuple stormTupleRcv) {
		// TODO
		// short circuit that this is a window configuration
		if (WindowSemanticsManager.evictStateIfSlidingWindowSemantics(this,
				stormTupleRcv)) {
			return;
		}

		if (_firstTime
				&& MyUtilities.isAggBatchOutputMode(_aggBatchOutputMillis)) {
			_periodicAggBatch = new PeriodicAggBatchSend(_aggBatchOutputMillis,
					this);
			_firstTime = false;
		}
		if (receivedDumpSignal(stormTupleRcv)) {
			MyUtilities.dumpSignal(this, stormTupleRcv, getCollector());
			return;
		}
		if (!MyUtilities.isManualBatchingMode(getConf())) {
			final String inputComponentIndex = stormTupleRcv
					.getStringByField(StormComponent.COMP_INDEX); // getString(0);
			final List<String> tuple = (List<String>) stormTupleRcv
					.getValueByField(StormComponent.TUPLE); // getValue(1);
			final String inputTupleHash = stormTupleRcv
					.getStringByField(StormComponent.HASH);// getString(2);
			if (processFinalAck(tuple, stormTupleRcv))
				return;
			final String inputTupleString = MyUtilities.tupleToString(tuple,
					getConf());
			processNonLastTuple(inputComponentIndex, tuple, inputTupleHash,
					stormTupleRcv, true, _firstRelationStorage,
					_secondRelationStorage);
		} else {
			final String inputComponentIndex = stormTupleRcv
					.getStringByField(StormComponent.COMP_INDEX); // getString(0);
			final String inputBatch = stormTupleRcv
					.getStringByField(StormComponent.TUPLE);// getString(1);
			final String[] wholeTuples = inputBatch
					.split(SystemParameters.MANUAL_BATCH_TUPLE_DELIMITER);
			final int batchSize = wholeTuples.length;
			for (int i = 0; i < batchSize; i++) {
				// parsing
				final String currentTuple = new String(wholeTuples[i]);
				final String[] parts = currentTuple
						.split(SystemParameters.MANUAL_BATCH_HASH_DELIMITER);
				String inputTupleHash = null;
				String inputTupleString = null;
				if (parts.length == 1)
					// lastAck
					inputTupleString = new String(parts[0]);
				else {
					inputTupleHash = new String(parts[0]);
					inputTupleString = new String(parts[1]);
				}
				final List<String> tuple = MyUtilities.stringToTuple(
						inputTupleString, getConf());
				// final Ack check
				if (processFinalAck(tuple, stormTupleRcv)) {
					if (i != batchSize - 1)
						throw new RuntimeException(
								"Should not be here. LAST_ACK is not the last tuple!");
					return;
				}
				// processing a tuple
				if (i == batchSize - 1)
					processNonLastTuple(inputComponentIndex, tuple,
							inputTupleHash, stormTupleRcv, true,
							_firstRelationStorage, _secondRelationStorage);
				else
					processNonLastTuple(inputComponentIndex, tuple,
							inputTupleHash, stormTupleRcv, false,
							_firstRelationStorage, _secondRelationStorage);
			}
		}
		// TODO
		// Update LatestTimeStamp
		WindowSemanticsManager.updateLatestTimeStamp(this, stormTupleRcv);
		getCollector().ack(stormTupleRcv);
	}

	@Override
	protected void finalizeProcessing() {
		super.finalizeProcessing();
		// BDB shutdown
		_firstRelationStorage.shutdown();

		String storagePath = null;
		if (SystemParameters.getBoolean(getConf(), "DIP_DISTRIBUTED"))
			storagePath = SystemParameters.getString(getConf(),
					"STORAGE_CLUSTER_DIR");
		else
			storagePath = SystemParameters.getString(getConf(),
					"STORAGE_LOCAL_DIR");
		File f = new File(storagePath + "/first/" + this.getName()
				+ _thisTaskID);
		f.delete();
		_secondRelationStorage.shutdown();
		f = new File(storagePath + "/second/" + this.getName() + _thisTaskID);
		f.delete();
	}

	@Override
	public void prepare(Map map, TopologyContext tc, OutputCollector collector) {
		super.prepare(map, tc, collector);
		createStorage(_firstRelationStorage, _secondRelationStorage, LOG);
	}

	@Override
	protected void printStatistics(int type) {
		printStatistics(type, _firstRelationStorage.size(),
				_secondRelationStorage.size(), LOG);
		LOG.info("First Storage: " + _firstRelationStorage.getStatistics()
				+ "End of First Storage\n");
		LOG.info("Second Storage: " + _secondRelationStorage.getStatistics()
				+ "End of Second Storage\n");
	}

	@Override
	public void purgeStaleStateFromWindow() {
		// TODO WINDOW Semantics
		_firstRelationStorage.purgeState(_latestTimeStamp
				- WindowSemanticsManager._GC_PERIODIC_TICK);
		_secondRelationStorage.purgeState(_latestTimeStamp
				- WindowSemanticsManager._GC_PERIODIC_TICK);
		System.gc();
	}
}