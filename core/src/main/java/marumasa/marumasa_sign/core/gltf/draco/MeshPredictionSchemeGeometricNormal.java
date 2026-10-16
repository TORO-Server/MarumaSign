package marumasa.marumasa_sign.core.gltf.draco;
class MeshPredictionSchemeGeometricNormal extends MeshPredictionScheme
{    
    MeshPredictionSchemeGeometricNormalPredictorArea predictor_;
    RAnsBitDecoder flip_normal_bit_decoder_;
    OctahedronToolBox octahedron_tool_box_;
    public MeshPredictionSchemeGeometricNormal(PointAttribute attribute, PredictionSchemeTransform transform, MeshPredictionSchemeData meshData)
    {
        super(attribute, transform, meshData);
        this.$initFields$();
        this.predictor_ = new MeshPredictionSchemeGeometricNormalPredictorArea(meshData);
        this.octahedron_tool_box_ = new OctahedronToolBox();
        this.flip_normal_bit_decoder_ = new RAnsBitDecoder();
    }
    
    public int getNumParentAttributes()
    {
        return 1;
    }
    
    @Override
    public int getParentAttributeType(int i)
    {
        return AttributeType.POSITION;
    }
    
    @Override
    public void setParentAttribute(PointAttribute att)
        throws DrakoException
    {
        if (att.getAttributeType() != AttributeType.POSITION)
            throw DracoUtils.failed();
        // Invalid attribute type.
        if (att.getComponentsCount() != 3)
            throw DracoUtils.failed();
        // Currently works only for 3 component positions.
        predictor_.pos_attribute_ = att;
    }
    
        @Override
    public void computeCorrectionValues(IntSpan in_data, IntSpan out_corr, int size, int num_components, int[] entry_to_point_id_map) {
        throw new UnsupportedOperationException();
    }
    public boolean getInitialized() {
        
        if (!predictor_.isInitialized())
            return false;
        //if (!meshData.Initialized)
        //    return DracoUtils.Failed();
        if (!octahedron_tool_box_.isInitialized())
            return false;
        return true;
    }
    
    @Override
    public void computeOriginalValues(IntSpan inCorr, IntSpan outData, int size, int numComponents, int[] entryToPointIdMap)
    {
        final int[] ref0 = new int[1];
        final int[] ref1 = new int[1];
        octahedron_tool_box_.setQuantizationBits(((PredictionSchemeNormalOctahedronTransformBase)this.transform_).getQuantizationBits());
        predictor_.entry_to_point_id_map_ = entryToPointIdMap;
        int corner_map_size = this.meshData.dataToCornerMap.getCount();
        int[] pred_normal_3d = new int[3];
        IntSpan pred_normal_oct = IntSpan.wrap(new int[2]);
        
        for (int data_id = 0; data_id < corner_map_size; ++data_id)
        {
            int corner_id = this.meshData.dataToCornerMap.get(data_id);
            predictor_.computePredictedValue(corner_id, pred_normal_3d);
            
            // Compute predicted octahedral coordinates.
            octahedron_tool_box_.canonicalizeIntegerVector(pred_normal_3d);
            if (flip_normal_bit_decoder_.decodeNextBit())
            {
                pred_normal_3d[0] = -pred_normal_3d[0];
                pred_normal_3d[1] = -pred_normal_3d[1];
                pred_normal_3d[2] = -pred_normal_3d[2];
            }
            
            int s;
            int t;
            octahedron_tool_box_.integerVectorToQuantizedOctahedralCoords(pred_normal_3d, ref0, ref1);
            s = ref0[0];
            t = ref1[0];
            pred_normal_oct.put(0, s);
            pred_normal_oct.put(1, t);
            int data_offset = data_id * 2;
            this.transform_.computeOriginalValue(pred_normal_oct, 0, inCorr, data_offset, outData, data_offset);
        }
        
        
        flip_normal_bit_decoder_.endDecoding();
    }
    
    @Override
    public void decodePredictionData(DecoderBuffer buffer)
        throws DrakoException
    {
        this.transform_.decodeTransformData(buffer);
        
        if (buffer.getBitstreamVersion() < 22)
        {
            byte prediction_mode = buffer.decodeU8();
            
            if (!predictor_.setNormalPredictionMode(prediction_mode))
                throw DracoUtils.failed();
        }
        
        
        // Init normal flips.
        flip_normal_bit_decoder_.startDecoding(buffer);
    }
    
    public int getPredictionMethod()
    {
        return PredictionSchemeMethod.GEOMETRIC_NORMAL;
    }
    
        private void $initFields$()
    {
        try
        {
            }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
        
    }
    
}
